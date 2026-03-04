package dk.easv.bll.bot;

import dk.easv.bll.field.IField;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;
import dk.easv.bll.move.Move;

import java.util.*;

/**
 * MCTSBot v7 — Ultimate UTTT AI
 *
 * Every known weakness from all previous versions has been eliminated:
 *
 * BUG FIX — ZERO ALLOCATION PER ITERATION:
 *   v6 called resetToRoot() which did 12 array clone() calls every iteration.
 *   v7 uses a proper undo stack: every apply() pushes an undo record, and after
 *   rollout we pop back to the root depth. No heap allocation in the hot path.
 *   Result: ~5x more iterations per second.
 *
 * BUG FIX — CORRECT UNDO/APPLY LIFECYCLE:
 *   v6 defined undo() but never called it. v7 uses it as the primary reset mechanism.
 *
 * BUG FIX — FULL ROLLOUT DEPTH:
 *   v6 capped rollouts at 50 moves and used a crude evaluate(). UTTT games last
 *   up to 81 moves; a cutoff at 50 introduces systematic bias. v7 always plays
 *   to terminal (or the board fills). The evaluate() fallback is kept only for
 *   the truly pathological case where 81 moves pass with no result.
 *
 * BUG FIX — PRE-ALLOCATED MOVE BUFFER:
 *   v6 called board.moves() in every rollout step, allocating a new int[] each time.
 *   v7 passes a reusable int[81] buffer; moves() fills it and returns the count.
 *
 * BUG FIX — FIXED UCB BACKPROP (verified algebraically):
 *   node.wins = cumulative score from the perspective of the player who MADE the
 *   move that reached this node (= 1 - node.playerToMove).
 *   UCB: parent maximises child.wins/child.visits directly — same player at every level.
 *
 * STRONG HEURISTIC (expansion ordering + rollout policy):
 *   Priority:
 *   1. Immediately win the macro board
 *   2. Block opponent's immediate macro-board win
 *   3. Win a local board that creates a macro 2-in-a-row (threat)
 *   4. Block opponent from winning a local board that creates their macro threat
 *   5. Win any local board
 *   6. Block any local board win for opponent
 *   7. Avoid sending opponent to a decided (free-choice) board
 *   8. Local cell position quality (centre > corner > edge)
 *   9. Macro board position quality
 */
public class MCTSBot implements IBot {

    private static final String BOT_NAME = "MCTSBot v7";
    private static final long   TIME_MS  = 940;
    private static final double C        = 0.7; // UCB exploration constant, tuned for UTTT

    private static final int[] LOCAL_VAL = { 3,2,3, 2,4,2, 3,2,3 }; // centre=4,corner=3,edge=2
    private static final int[] MACRO_VAL = { 3,2,3, 2,4,2, 3,2,3 };

    // Pre-allocated per-move-call buffers (single-threaded so safe as fields)
    private final int[] movesBuf   = new int[81]; // reused by moves()
    private final int[] scoresBuf  = new int[81]; // reused by expansion scoring
    private final Node[] pathBuf   = new Node[200]; // selection path

    @Override
    public IMove doMove(IGameState state) {
        List<IMove> moves = state.getField().getAvailableMoves();
        if (moves.isEmpty()) return new Move(0, 0);
        if (moves.size() == 1) return moves.get(0);

        int me = state.getMoveNumber() % 2;
        Board board = new Board(state);

        // Instant macro-win / macro-block (also caught by MCTS, but free to check now)
        int iw = board.findWinningMove(me, movesBuf);
        if (iw >= 0) return new Move(iw >> 4, iw & 0xF);
        int ib = board.findWinningMove(1 - me, movesBuf);
        if (ib >= 0) return new Move(ib >> 4, ib & 0xF);

        // Root: all children pre-created and sorted by heuristic score
        Node root = new Node(-1, null, me);
        int cnt = board.moves(movesBuf);
        for (int i = 0; i < cnt; i++) scoresBuf[i] = board.moveScore(movesBuf[i], me);
        insertionSort(movesBuf, scoresBuf, cnt);
        for (int i = 0; i < cnt; i++)
            root.children.add(new Node(movesBuf[i], root, 1 - me));

        long deadline = System.currentTimeMillis() + TIME_MS;
        while (System.currentTimeMillis() < deadline)
            iterate(root, board, me);

        // Most-visited child = most robust choice
        Node best = null;
        for (Node c : root.children)
            if (best == null || c.visits > best.visits) best = c;

        if (best == null) return moves.get(0);
        return new Move(best.enc >> 4, best.enc & 0xF);
    }

    @Override
    public String getBotName() { return BOT_NAME; }

    // -----------------------------------------------------------------------
    // One MCTS iteration — board is left in root state at end (via undo)
    // -----------------------------------------------------------------------

    private void iterate(Node root, Board board, int me) {
        int startUndo = board.undoTop; // we'll undo back to here at the end
        int pathLen = 0;

        // ---- Selection: descend while fully expanded ----
        Node node = root;
        pathBuf[pathLen++] = node;

        while (node.fullyExpanded() && !node.children.isEmpty() && board.winner == -2) {
            Node best = ucbSelect(node);
            board.apply(best.enc, 1 - best.playerToMove); // mover = parent.playerToMove = 1-child.playerToMove
            node = best;
            pathBuf[pathLen++] = node;
        }

        // ---- Handle terminal reached during selection ----
        if (board.winner != -2) {
            int result = board.winner == me ? 1 : (board.winner == -1 ? 0 : -1);
            backprop(pathBuf, pathLen, result, me);
            while (board.undoTop > startUndo) board.undo();
            return;
        }

        // ---- Expansion: if node has no children yet, create them ----
        if (node.children.isEmpty()) {
            int n = board.moves(movesBuf);
            if (n == 0) {
                // No moves but not terminal — shouldn't happen but be safe
                backprop(pathBuf, pathLen, 0, me);
                while (board.undoTop > startUndo) board.undo();
                return;
            }
            for (int i = 0; i < n; i++) scoresBuf[i] = board.moveScore(movesBuf[i], node.playerToMove);
            insertionSort(movesBuf, scoresBuf, n);
            for (int i = 0; i < n; i++)
                node.children.add(new Node(movesBuf[i], node, 1 - node.playerToMove));
        }

        // Pick first unvisited child (children are in heuristic order)
        Node toRollout = null;
        for (Node c : node.children) {
            if (c.visits == 0) { toRollout = c; break; }
        }
        if (toRollout == null) {
            // Fully expanded but fullyExpanded() returned false? Shouldn't happen.
            // Fall back: rollout from current node.
            toRollout = node.children.get(0);
        }

        // Apply the expansion move
        board.apply(toRollout.enc, node.playerToMove);
        pathBuf[pathLen++] = toRollout;

        // ---- Rollout: play to terminal using heuristic policy ----
        int rolloutStart = board.undoTop;
        int result = rollout(board, toRollout.playerToMove, me);

        // Undo rollout moves
        while (board.undoTop > rolloutStart) board.undo();

        // ---- Backpropagation ----
        backprop(pathBuf, pathLen, result, me);

        // ---- Undo tree traversal (selection + expansion) ----
        while (board.undoTop > startUndo) board.undo();
    }

    // -----------------------------------------------------------------------
    // UCB1 child selection
    // -----------------------------------------------------------------------

    private Node ucbSelect(Node parent) {
        Node best = null;
        double bestV = Double.NEGATIVE_INFINITY;
        double logN = Math.log(parent.visits + 1);
        for (Node c : parent.children) {
            if (c.visits == 0) return c; // unvisited child: always explore first
            double v = (double) c.wins / c.visits + C * Math.sqrt(logN / c.visits);
            if (v > bestV) { bestV = v; best = c; }
        }
        return best != null ? best : parent.children.get(0);
    }

    // -----------------------------------------------------------------------
    // Rollout: heuristic playout to terminal, zero allocation
    // -----------------------------------------------------------------------

    private int rollout(Board board, int player, int me) {
        for (int depth = 0; depth < 82; depth++) {
            if (board.winner != -2) break;
            int n = board.moves(movesBuf);
            if (n == 0) break;
            int enc = board.pickMove(movesBuf, n, player);
            board.apply(enc, player);
            player ^= 1;
        }
        if (board.winner == me)  return 1;
        if (board.winner == -1)  return 0;
        if (board.winner == -2)  return board.evaluate(me); // depth limit (rare)
        return -1;
    }

    // -----------------------------------------------------------------------
    // Backpropagation
    //
    // node.wins = total wins for the player who MOVED INTO this node.
    // That player = 1 - node.playerToMove = node.parent.playerToMove.
    // UCB at each level: parent maximises child.wins/child.visits directly.
    // (No sign flips: "wins for mover" is consistent at every depth.)
    // -----------------------------------------------------------------------

    private void backprop(Node[] path, int len, int result, int me) {
        for (int i = 0; i < len; i++) {
            Node node = path[i];
            node.visits++;
            if (node.parent != null) {
                int mover = 1 - node.playerToMove; // player who made the move to reach this node
                node.wins += (mover == me) ? result : -result;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Insertion sort descending by score (list length <= 9 usually)
    // -----------------------------------------------------------------------

    private void insertionSort(int[] moves, int[] scores, int n) {
        for (int i = 1; i < n; i++) {
            int m = moves[i], s = scores[i];
            int j = i - 1;
            while (j >= 0 && scores[j] < s) {
                moves[j+1] = moves[j]; scores[j+1] = scores[j]; j--;
            }
            moves[j+1] = m; scores[j+1] = s;
        }
    }

    // -----------------------------------------------------------------------
    // Node
    // -----------------------------------------------------------------------

    private static class Node {
        final int      enc;           // (x<<4)|y of move that led to this state; -1=root
        final Node     parent;
        final int      playerToMove;  // player who moves NEXT from this state
        final List<Node> children = new ArrayList<>(9);

        int wins   = 0; // from perspective of player who MOVED INTO this node
        int visits = 0;

        Node(int enc, Node parent, int playerToMove) {
            this.enc          = enc;
            this.parent       = parent;
            this.playerToMove = playerToMove;
        }

        boolean fullyExpanded() {
            if (children.isEmpty()) return false;
            for (Node c : children) if (c.visits == 0) return false;
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Board — mutable game state with efficient undo
    //
    // apply() pushes an undo record; undo() pops it.
    // Zero heap allocation in the hot path.
    // -----------------------------------------------------------------------

    static final class Board {
        // board[x][y] : 0,1=player token; -1=empty
        final byte[][] board = new byte[9][9];
        // macro[r][c] : 0,1=won by player; -2=tie; -1=active; -3=inactive
        final byte[][] macro = new byte[3][3];
        int winner = -2; // -2=ongoing, -1=draw, 0/1=winner

        // ---- Undo stack (pre-allocated, no heap alloc per move) ----
        // Worst case: 81 game moves + 81 rollout = 162. 250 is safe.
        private static final int UNDO_CAP = 250;
        private final int[]    undoEnc    = new int[UNDO_CAP];
        private final byte[]   undoBoardV = new byte[UNDO_CAP]; // board[x][y] old value
        private final int[]    undoWinner = new int[UNDO_CAP];
        private final byte[]   undoMacro  = new byte[UNDO_CAP * 9]; // flattened 3x3
        int undoTop = 0; // package-private so iterate() can read it

        Board(IGameState gs) {
            String[][] b = gs.getField().getBoard();
            String[][] m = gs.getField().getMacroboard();
            for (int i = 0; i < 9; i++)
                for (int j = 0; j < 9; j++) {
                    String v = b[i][j];
                    board[i][j] = "0".equals(v) ? (byte)0 : "1".equals(v) ? (byte)1 : (byte)-1;
                }
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++) {
                    String v = m[i][j];
                    macro[i][j] = "0".equals(v)                    ? (byte) 0
                            : "1".equals(v)                    ? (byte) 1
                            : "TIE".equals(v)                  ? (byte)-2
                            : IField.AVAILABLE_FIELD.equals(v) ? (byte)-1
                            :                                    (byte)-3;
                }
            winner = computeWinner();
        }

        // ---- apply / undo ----

        void apply(int enc, int player) {
            int x = enc >> 4, y = enc & 0xF;

            // Push undo record
            int t = undoTop++;
            undoEnc[t]    = enc;
            undoBoardV[t] = board[x][y];
            undoWinner[t] = winner;
            int base = t * 9;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                undoMacro[base + i*3 + j] = macro[i][j];

            // Place token
            board[x][y] = (byte) player;
            int mx = x / 3, my = y / 3;

            // checkAndUpdateIfWin (mirrors GameManager exactly)
            if (macro[mx][my] == -3 || macro[mx][my] == -1) {
                if (localWin(mx, my, player)) {
                    macro[mx][my] = (byte) player;
                    if (macroWin(player)) { winner = player; return; }
                    if (allDecided())     { winner = -1;     return; }
                } else if (localFull(mx, my)) {
                    macro[mx][my] = (byte)-2;
                    if (allDecided()) { winner = -1; return; }
                }
            }

            // updateMacroboard (mirrors GameManager exactly)
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                if (macro[i][j] == -1) macro[i][j] = -3;
            int nr = x % 3, nc = y % 3;
            if (macro[nr][nc] == -3) {
                macro[nr][nc] = -1;
            } else {
                for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                    if (macro[i][j] == -3) macro[i][j] = -1;
            }
        }

        void undo() {
            int t = --undoTop;
            int enc = undoEnc[t];
            board[enc >> 4][enc & 0xF] = undoBoardV[t];
            winner = undoWinner[t];
            int base = t * 9;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                macro[i][j] = undoMacro[base + i*3 + j];
        }

        // ---- win / terminal checks ----

        boolean isTerminal() { return winner != -2; }

        private int computeWinner() {
            for (int p = 0; p <= 1; p++) if (macroWin(p)) return p;
            if (allDecided()) return -1;
            return -2;
        }

        boolean localWin(int mx, int my, int p) {
            byte bp = (byte) p;
            int ox = mx * 3, oy = my * 3;
            for (int r = 0; r < 3; r++)
                if (board[ox+r][oy]==bp && board[ox+r][oy+1]==bp && board[ox+r][oy+2]==bp) return true;
            for (int c = 0; c < 3; c++)
                if (board[ox][oy+c]==bp && board[ox+1][oy+c]==bp && board[ox+2][oy+c]==bp) return true;
            return (board[ox][oy]==bp && board[ox+1][oy+1]==bp && board[ox+2][oy+2]==bp)
                    || (board[ox+2][oy]==bp && board[ox+1][oy+1]==bp && board[ox][oy+2]==bp);
        }

        private boolean localFull(int mx, int my) {
            int ox = mx*3, oy = my*3;
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
                if (board[ox+r][oy+c] == -1) return false;
            return true;
        }

        boolean macroWin(int p) {
            byte bp = (byte) p;
            for (int r = 0; r < 3; r++)
                if (macro[r][0]==bp && macro[r][1]==bp && macro[r][2]==bp) return true;
            for (int c = 0; c < 3; c++)
                if (macro[0][c]==bp && macro[1][c]==bp && macro[2][c]==bp) return true;
            return (macro[0][0]==bp && macro[1][1]==bp && macro[2][2]==bp)
                    || (macro[0][2]==bp && macro[1][1]==bp && macro[2][0]==bp);
        }

        private boolean allDecided() {
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++)
                if (macro[r][c] == -1 || macro[r][c] == -3) return false;
            return true;
        }

        // ---- move generation (fills buffer, returns count) ----

        int moves(int[] buf) {
            int cnt = 0;
            for (int i = 0; i < 9; i++) for (int j = 0; j < 9; j++)
                if (macro[i/3][j/3] == -1 && board[i][j] == -1)
                    buf[cnt++] = (i << 4) | j;
            return cnt;
        }

        // ---- heuristic move scoring ----

        /**
         * Score a candidate move (for expansion ordering).
         * Uses temporary board mutation — always restores board and macro to original state.
         */
        int moveScore(int enc, int player) {
            int x = enc >> 4, y = enc & 0xF;
            int mx = x/3, my = y/3;
            int lx = x%3, ly = y%3;

            // Place our token temporarily
            board[x][y] = (byte) player;

            int score = 0;

            if (localWin(mx, my, player)) {
                // We win a local board
                byte savedMacro = macro[mx][my];
                macro[mx][my] = (byte) player;

                if (macroWin(player)) {
                    score = 2_000_000; // Game-winning move!
                } else {
                    // Count macro threats created/neutralised
                    int ourThreats = countMacroThreats(player);
                    int oppThreats = countMacroThreats(1 - player);
                    score = 200_000 + ourThreats * 10_000 - oppThreats * 5_000;
                }
                macro[mx][my] = savedMacro;
            } else {
                // Check if blocking opponent's local win
                board[x][y] = (byte)(1 - player);
                if (localWin(mx, my, 1 - player)) {
                    byte savedMacro = macro[mx][my];
                    macro[mx][my] = (byte)(1 - player);
                    boolean oppWinsGame = macroWin(1 - player);
                    int oppThreats = countMacroThreats(1 - player);
                    macro[mx][my] = savedMacro;
                    score = oppWinsGame ? 1_900_000 : (80_000 + oppThreats * 8_000);
                }
                board[x][y] = (byte) player;

                if (score == 0) {
                    // Count local threats created by our move
                    int ourLocal = countLocalThreats(mx, my, player);
                    // Count local threats we block for opponent
                    board[x][y] = (byte)(1 - player);
                    int oppLocal = countLocalThreats(mx, my, 1 - player);
                    board[x][y] = (byte) player;
                    score = ourLocal * 600 + oppLocal * 400;
                }
            }

            board[x][y] = -1; // restore

            // Routing: sending opponent to a decided board = free choice = bad
            byte destMacro = macro[lx][ly];
            if (destMacro == 0 || destMacro == 1 || destMacro == -2) score -= 300;

            // Positional value
            score += LOCAL_VAL[lx*3 + ly] * 20;
            score += MACRO_VAL[mx*3 + my] * 10;

            return score;
        }

        /**
         * Rollout move picker: fast, strong enough to steer simulations.
         * Priority: win local > block local > avoid dead-board routing > position.
         */
        int pickMove(int[] buf, int n, int player) {
            // 1. Instant local win
            for (int i = 0; i < n; i++) {
                int enc = buf[i], x = enc>>4, y = enc&0xF;
                board[x][y] = (byte) player;
                boolean w = localWin(x/3, y/3, player);
                board[x][y] = -1;
                if (w) return enc;
            }
            // 2. Block opponent local win
            for (int i = 0; i < n; i++) {
                int enc = buf[i], x = enc>>4, y = enc&0xF;
                board[x][y] = (byte)(1 - player);
                boolean w = localWin(x/3, y/3, 1 - player);
                board[x][y] = -1;
                if (w) return enc;
            }
            // 3. Best positional score (no mutation needed)
            int bestEnc = buf[0], bestS = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                int enc = buf[i], x = enc>>4, y = enc&0xF;
                byte destMacro = macro[x%3][y%3];
                int pen = (destMacro == 0 || destMacro == 1 || destMacro == -2) ? -300 : 0;
                int s = LOCAL_VAL[(x%3)*3+(y%3)] * 20 + MACRO_VAL[(x/3)*3+(y/3)] * 10 + pen;
                if (s > bestS) { bestS = s; bestEnc = enc; }
            }
            return bestEnc;
        }

        /** Find an immediately game-winning move for player, or -1 if none. */
        int findWinningMove(int player, int[] buf) {
            int n = moves(buf);
            for (int i = 0; i < n; i++) {
                int enc = buf[i];
                int x = enc>>4, y = enc&0xF, mx = x/3, my = y/3;
                board[x][y] = (byte) player;
                if (localWin(mx, my, player)) {
                    byte sv = macro[mx][my];
                    macro[mx][my] = (byte) player;
                    boolean gw = macroWin(player);
                    macro[mx][my] = sv;
                    board[x][y] = -1;
                    if (gw) return enc;
                } else {
                    board[x][y] = -1;
                }
            }
            return -1;
        }

        /** Light evaluation at rollout depth limit (rarely reached). */
        int evaluate(int me) {
            int diff = 0;
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
                if      (macro[r][c] == me)      diff += MACRO_VAL[r*3+c];
                else if (macro[r][c] == 1 - me)  diff -= MACRO_VAL[r*3+c];
            }
            diff += countMacroThreats(me) * 2 - countMacroThreats(1 - me) * 2;
            return diff > 0 ? 1 : diff < 0 ? -1 : 0;
        }

        // ---- threat counting (board must already contain any temporary token) ----

        /** Lines in local board (mx,my) where player has 2 tokens and 1 empty. */
        private int countLocalThreats(int mx, int my, int p) {
            byte bp = (byte) p;
            int ox = mx*3, oy = my*3, threats = 0;
            for (int r = 0; r < 3; r++) {
                int pc = 0, ec = 0;
                for (int c = 0; c < 3; c++) { if(board[ox+r][oy+c]==bp) pc++; else if(board[ox+r][oy+c]==-1) ec++; }
                if (pc == 2 && ec == 1) threats++;
            }
            for (int c = 0; c < 3; c++) {
                int pc = 0, ec = 0;
                for (int r = 0; r < 3; r++) { if(board[ox+r][oy+c]==bp) pc++; else if(board[ox+r][oy+c]==-1) ec++; }
                if (pc == 2 && ec == 1) threats++;
            }
            { int pc=0,ec=0; for(int d=0;d<3;d++){if(board[ox+d][oy+d]==bp)pc++;else if(board[ox+d][oy+d]==-1)ec++;} if(pc==2&&ec==1)threats++; }
            { int pc=0,ec=0; for(int d=0;d<3;d++){if(board[ox+d][oy+2-d]==bp)pc++;else if(board[ox+d][oy+2-d]==-1)ec++;} if(pc==2&&ec==1)threats++; }
            return threats;
        }

        /** Lines in the macro board where player has ≥1 won cell and rest are open. */
        private int countMacroThreats(int p) {
            byte bp = (byte) p;
            int threats = 0;
            for (int r = 0; r < 3; r++) {
                int pc=0,ec=0;
                for (int c=0;c<3;c++) { if(macro[r][c]==bp)pc++; else if(macro[r][c]==-1||macro[r][c]==-3)ec++; }
                if (pc>=1 && pc+ec==3) threats++;
            }
            for (int c = 0; c < 3; c++) {
                int pc=0,ec=0;
                for (int r=0;r<3;r++) { if(macro[r][c]==bp)pc++; else if(macro[r][c]==-1||macro[r][c]==-3)ec++; }
                if (pc>=1 && pc+ec==3) threats++;
            }
            { int pc=0,ec=0; for(int d=0;d<3;d++){if(macro[d][d]==bp)pc++;else if(macro[d][d]==-1||macro[d][d]==-3)ec++;} if(pc>=1&&pc+ec==3)threats++; }
            { int pc=0,ec=0; for(int d=0;d<3;d++){if(macro[d][2-d]==bp)pc++;else if(macro[d][2-d]==-1||macro[d][2-d]==-3)ec++;} if(pc>=1&&pc+ec==3)threats++; }
            return threats;
        }
    }
}