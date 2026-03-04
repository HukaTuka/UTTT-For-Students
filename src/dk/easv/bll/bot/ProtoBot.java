package dk.easv.bll.bot;

import dk.easv.bll.field.IField;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;

import java.util.*;

/**
 * ProtoBot — minimax + alpha-beta + TT + killer moves
 * Uses a zero-allocation byte board with undo stack (same architecture as MCTSBot v7)
 * so it searches ~50x deeper than the string-copy version.
 */
public class ProtoBot implements IBot {
    static final String BOTNAME = "ProtoBot";
    static private String[][][] allStates;
    private final int totalSize = (int) Math.pow(3, 9);

    private static final int MAX_DEPTH  = 14;
    private static final long TIME_MS   = 930;

    // Positional weights
    private static final int[] POS = {3,2,3, 2,4,2, 3,2,3};

    // Zobrist
    private static final long[][] ZOB = new long[81][3];
    static {
        Random rng = new Random(0xDEADBEEFL);
        for (int i = 0; i < 81; i++)
            for (int j = 0; j < 3; j++)
                ZOB[i][j] = rng.nextLong();
    }

    // Transposition table
    private static final int TT = 1 << 23;
    private final long[] ttKey   = new long[TT];
    private final int[]  ttVal   = new int[TT];
    private final byte[] ttDep   = new byte[TT];
    private final byte[] ttFlg   = new byte[TT]; // 0=exact 1=lower 2=upper

    // Killer moves [ply][slot]
    private final int[][] killer = new int[MAX_DEPTH + 4][2];

    // The fast board (reused across the entire search)
    private final FastBoard fb = new FastBoard();

    private long deadline;

    public ProtoBot() {
        allStates = new String[totalSize][3][3];
        generateAllStates();
    }

    // =========================================================================
    // IBot
    // =========================================================================

    @Override
    public IMove doMove(IGameState state) {
        List<IMove> rawMoves = state.getField().getAvailableMoves();
        if (rawMoves.size() == 1) return rawMoves.get(0);

        deadline = System.currentTimeMillis() + TIME_MS;

        // Load position into fast board
        fb.load(state);

        // Instant win / block
        int iw = fb.findGameWin(fb.me);
        if (iw >= 0) return dec(iw);
        int ib = fb.findGameWin(fb.opp);
        if (ib >= 0) return dec(ib);

        // Clear killers
        for (int[] k : killer) { k[0] = -1; k[1] = -1; }

        int bestEnc  = fb.moves()[0];
        int bestScore = Integer.MIN_VALUE;

        // Iterative deepening
        for (int depth = 2; depth <= MAX_DEPTH; depth++) {
            if (System.currentTimeMillis() >= deadline) break;
            int[] result = rootSearch(depth, bestScore);
            if (result != null) { bestEnc = result[0]; bestScore = result[1]; }
            if (bestScore >= 9000) break; // forced win found
        }
        return dec(bestEnc);
    }

    @Override
    public String getBotName() { return BOTNAME; }

    // =========================================================================
    // Root search — returns [enc, score] or null if timed out immediately
    // =========================================================================

    private int[] rootSearch(int depth, int prevBest) {
        int[] moves = fb.moves();
        moves = order(moves, String.valueOf(fb.me), 0);

        int bestEnc  = -1;
        int bestScore = Integer.MIN_VALUE;
        int alpha = Integer.MIN_VALUE, beta = Integer.MAX_VALUE;

        for (int enc : moves) {
            if (System.currentTimeMillis() >= deadline) break;
            fb.apply(enc, fb.me);
            long h = fb.hash;
            int score = minimax(depth - 1, false, alpha, beta, h, 1);
            fb.undo();

            if (score > bestScore) { bestScore = score; bestEnc = enc; }
            alpha = Math.max(alpha, bestScore);
        }
        return bestEnc >= 0 ? new int[]{bestEnc, bestScore} : null;
    }

    // =========================================================================
    // Minimax
    // =========================================================================

    private int minimax(int depth, boolean maxing, int alpha, int beta, long hash, int ply) {
        int w = fb.winner;
        if (w == fb.me)  return 10000 + depth;
        if (w == fb.opp) return -10000 - depth;
        if (w == 2)      return 0; // draw

        int[] moves = fb.moves();
        if (moves.length == 0) return 0;

        if (depth == 0 || System.currentTimeMillis() >= deadline)
            return fb.evaluate();

        // TT lookup
        int idx = (int)(hash & (TT - 1));
        if (ttKey[idx] == hash && (ttDep[idx] & 0xFF) >= depth) {
            int cv = ttVal[idx];
            if (ttFlg[idx] == 0) return cv;
            if (ttFlg[idx] == 1 && cv > alpha) alpha = cv;
            if (ttFlg[idx] == 2 && cv < beta)  beta  = cv;
            if (alpha >= beta) return cv;
        }

        int origAlpha = alpha;
        int best = maxing ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        String player = maxing ? fb.meStr : fb.oppStr;
        moves = order(moves, player, ply);

        for (int enc : moves) {
            if (System.currentTimeMillis() >= deadline) break;
            fb.apply(enc, player);
            int score = minimax(depth - 1, !maxing, alpha, beta, fb.hash, ply + 1);
            fb.undo();

            if (maxing) {
                if (score > best) best = score;
                if (score > alpha) alpha = score;
            } else {
                if (score < best) best = score;
                if (score < beta) beta = score;
            }
            if (alpha >= beta) {
                if (enc != killer[ply][0]) { killer[ply][1] = killer[ply][0]; killer[ply][0] = enc; }
                break;
            }
        }

        // TT store
        ttKey[idx] = hash;
        ttVal[idx] = best;
        ttDep[idx] = (byte) Math.min(depth, 127);
        ttFlg[idx] = (byte)(best <= origAlpha ? 2 : best >= beta ? 1 : 0);

        return best;
    }

    // =========================================================================
    // Move ordering
    // =========================================================================

    private int[] order(int[] moves, String player, int ply) {
        String opp = fb.meStr.equals(player) ? fb.oppStr : fb.meStr;
        int n = moves.length;
        int[] scores = new int[n];

        for (int i = 0; i < n; i++) {
            int enc = moves[i];
            int x = enc >> 4, y = enc & 0xF;
            int mr = x/3, mc = y/3, lr = x%3, lc = y%3;
            int s = 0;

            if (fb.winsLocal(enc, player)) {
                if (fb.winsGame(enc, player)) s = 2_000_000;
                else s = 200_000 + fb.macroThreats(player, enc) * 10_000;
            } else if (fb.winsLocal(enc, opp)) {
                if (fb.winsGame(enc, opp)) s = 1_900_000;
                else s = 80_000 + fb.macroThreats(opp, enc) * 8_000;
            } else if (enc == killer[ply][0]) s = 50_000;
            else if (enc == killer[ply][1]) s = 49_000;
            else {
                // Routing penalty — sending to a decided board gives opponent free choice
                int sentMr = x % 3, sentMc = y % 3;
                int dest = fb.macroCell(sentMr, sentMc);
                boolean destDecided = (dest == 0 || dest == 1 || dest == FastBoard.DRAW_CELL);
                if (destDecided) s -= 3000;
                if (sentMr == 1 && sentMc == 1 && !destDecided) s -= 800;

                s += fb.localThreats(enc, player) * 600;
                s += POS[mr*3+mc] * 20 + POS[lr*3+lc] * 20;
            }
            scores[i] = s;
        }

        // Insertion sort descending (move lists are short, <= 9 usually)
        for (int i = 1; i < n; i++) {
            int m = moves[i], sv = scores[i], j = i - 1;
            while (j >= 0 && scores[j] < sv) {
                moves[j+1] = moves[j]; scores[j+1] = scores[j]; j--;
            }
            moves[j+1] = m; scores[j+1] = sv;
        }
        return moves;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static IMove dec(final int enc) {
        return new IMove() { public int getX(){return enc&0xF;} public int getY(){return enc>>4;} };
    }

    // =========================================================================
    // FastBoard — zero-allocation mutable board with undo stack
    // =========================================================================

    static final class FastBoard {
        static final int EMPTY_CELL = -1;
        static final int AVAIL_CELL = -2;
        static final int DRAW_CELL  = -3;

        // board[x][y]: 0=me, 1=opp, -1=empty
        final byte[][] board = new byte[9][9];
        // macro[r][c]: 0=me, 1=opp, -3=draw, -1=empty, -2=avail
        final byte[][] macro = new byte[3][3];

        int  winner  = -2; // -2=ongoing, 0=me, 1=opp, 2=draw
        int  me, opp;
        String meStr, oppStr;
        long hash;

        // Undo stack
        private static final int UNDO_CAP = 300;
        private final int[]  uEnc  = new int[UNDO_CAP];
        private final byte[] uBoard = new byte[UNDO_CAP];
        private final int[]  uWin  = new int[UNDO_CAP];
        private final byte[] uMacro = new byte[UNDO_CAP * 9];
        private final long[] uHash = new long[UNDO_CAP];
        private int top = 0;

        // Move buffer (reused, no allocation per call)
        private final int[] moveBuf = new int[81];

        void load(IGameState gs) {
            // Determine which player we are
            me  = gs.getMoveNumber() % 2;
            opp = 1 - me;
            meStr  = me  == 0 ? "0" : "1";
            oppStr = opp == 0 ? "0" : "1";

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
                    macro[i][j] = "0".equals(v)  ? (byte)0
                            : "1".equals(v)  ? (byte)1
                            : "D".equals(v)  ? (byte)DRAW_CELL
                            : IField.AVAILABLE_FIELD.equals(v) ? (byte)AVAIL_CELL
                            : (byte)EMPTY_CELL;
                }

            winner = computeWinner();
            hash = computeHash();
            top = 0;
        }

        void apply(int enc, String player) {
            int p = "0".equals(player) ? 0 : 1;
            apply(enc, p);
        }

        void apply(int enc, int p) {
            int x = enc >> 4, y = enc & 0xF;
            int t = top++;
            uEnc[t]  = enc;
            uBoard[t] = board[x][y];
            uWin[t]  = winner;
            uHash[t] = hash;
            int base = t * 9;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                uMacro[base + i*3 + j] = macro[i][j];

            // Update hash: remove empty, add player
            hash ^= ZOB[x*9+y][0] ^ ZOB[x*9+y][p+1];

            board[x][y] = (byte) p;
            int mx = x/3, my = y/3;

            if (macro[mx][my] == AVAIL_CELL || macro[mx][my] == EMPTY_CELL) {
                if (localWin(mx, my, p)) {
                    macro[mx][my] = (byte) p;
                    if (macroWin(p))   { winner = p;  return; }
                    if (allDecided())  { winner = 2;  return; }
                } else if (localFull(mx, my)) {
                    macro[mx][my] = (byte) DRAW_CELL;
                    if (allDecided()) { winner = 2; return; }
                }
            }

            // Update available boards
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                if (macro[i][j] == AVAIL_CELL) macro[i][j] = (byte) EMPTY_CELL;
            int nr = x%3, nc = y%3;
            byte dest = macro[nr][nc];
            // Rule: sent to a won or drawn board → free choice (play anywhere open)
            boolean decided = (dest == (byte)0 || dest == (byte)1 || dest == (byte)DRAW_CELL);
            if (!decided) macro[nr][nc] = (byte) AVAIL_CELL;
            else for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                if (macro[i][j] == EMPTY_CELL) macro[i][j] = (byte) AVAIL_CELL;
        }

        void undo() {
            int t = --top;
            int enc = uEnc[t];
            board[enc>>4][enc&0xF] = uBoard[t];
            winner = uWin[t];
            hash   = uHash[t];
            int base = t * 9;
            for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
                macro[i][j] = uMacro[base + i*3 + j];
        }

        int[] moves() {
            int cnt = 0;
            for (int i = 0; i < 9; i++) for (int j = 0; j < 9; j++)
                if (macro[i/3][j/3] == AVAIL_CELL && board[i][j] == -1)
                    moveBuf[cnt++] = (i<<4)|j;
            int[] out = new int[cnt];
            System.arraycopy(moveBuf, 0, out, 0, cnt);
            return out;
        }

        byte macroCell(int r, int c) { return macro[r][c]; }

        // ---- Win checks ----

        boolean localWin(int mx, int my, int p) {
            byte bp = (byte) p;
            int ox = mx*3, oy = my*3;
            for (int r=0;r<3;r++) if(board[ox+r][oy]==bp&&board[ox+r][oy+1]==bp&&board[ox+r][oy+2]==bp) return true;
            for (int c=0;c<3;c++) if(board[ox][oy+c]==bp&&board[ox+1][oy+c]==bp&&board[ox+2][oy+c]==bp) return true;
            return (board[ox][oy]==bp&&board[ox+1][oy+1]==bp&&board[ox+2][oy+2]==bp)
                    ||(board[ox+2][oy]==bp&&board[ox+1][oy+1]==bp&&board[ox][oy+2]==bp);
        }

        boolean macroWin(int p) {
            byte bp = (byte) p;
            for (int r=0;r<3;r++) if(macro[r][0]==bp&&macro[r][1]==bp&&macro[r][2]==bp) return true;
            for (int c=0;c<3;c++) if(macro[0][c]==bp&&macro[1][c]==bp&&macro[2][c]==bp) return true;
            return (macro[0][0]==bp&&macro[1][1]==bp&&macro[2][2]==bp)
                    ||(macro[0][2]==bp&&macro[1][1]==bp&&macro[2][0]==bp);
        }

        /** Temporarily place enc for player and check if they win their local board */
        boolean winsLocal(int enc, String player) {
            int p = "0".equals(player) ? 0 : 1;
            int x = enc>>4, y = enc&0xF;
            byte old = board[x][y];
            board[x][y] = (byte) p;
            boolean w = localWin(x/3, y/3, p);
            board[x][y] = old;
            return w;
        }

        /** Temporarily place enc for player and check if they win the whole game */
        boolean winsGame(int enc, String player) {
            int p = "0".equals(player) ? 0 : 1;
            int x = enc>>4, y = enc&0xF;
            byte oldBoard = board[x][y];
            byte oldMacro = macro[x/3][y/3];
            board[x][y] = (byte) p;
            boolean lw = localWin(x/3, y/3, p);
            if (lw) macro[x/3][y/3] = (byte) p;
            boolean gw = lw && macroWin(p);
            board[x][y] = oldBoard;
            macro[x/3][y/3] = oldMacro;
            return gw;
        }

        /** Find an immediately game-winning move for player, -1 if none */
        int findGameWin(int p) {
            int[] mv = moves();
            for (int enc : mv) if (winsGame(enc, p == me ? meStr : oppStr)) return enc;
            return -1;
        }

        // ---- Threat counting ----

        /** Count macro lines where player owns >=1 and rest are open, after placing enc */
        int macroThreats(String player, int enc) {
            int p = "0".equals(player) ? 0 : 1;
            int x = enc>>4, y = enc&0xF;
            byte oldBoard = board[x][y];
            byte oldMacro = macro[x/3][y/3];
            board[x][y] = (byte) p;
            if (localWin(x/3, y/3, p)) macro[x/3][y/3] = (byte) p;
            int threats = countMacroThreats(p);
            board[x][y] = oldBoard;
            macro[x/3][y/3] = oldMacro;
            return threats;
        }

        private int countMacroThreats(int p) {
            byte bp = (byte)p, op = (byte)(1-p);
            int threats = 0;
            for (int r=0;r<3;r++){int pc=0,ec=0;for(int c=0;c<3;c++){if(macro[r][c]==bp)pc++;else if(macro[r][c]!=op&&macro[r][c]!=DRAW_CELL)ec++;}if(pc>=1&&pc+ec==3)threats++;}
            for (int c=0;c<3;c++){int pc=0,ec=0;for(int r=0;r<3;r++){if(macro[r][c]==bp)pc++;else if(macro[r][c]!=op&&macro[r][c]!=DRAW_CELL)ec++;}if(pc>=1&&pc+ec==3)threats++;}
            {int pc=0,ec=0;for(int d=0;d<3;d++){if(macro[d][d]==bp)pc++;else if(macro[d][d]!=op&&macro[d][d]!=DRAW_CELL)ec++;}if(pc>=1&&pc+ec==3)threats++;}
            {int pc=0,ec=0;for(int d=0;d<3;d++){if(macro[d][2-d]==bp)pc++;else if(macro[d][2-d]!=op&&macro[d][2-d]!=DRAW_CELL)ec++;}if(pc>=1&&pc+ec==3)threats++;}
            return threats;
        }

        /** Count local 2-in-a-row threats after placing enc for player */
        int localThreats(int enc, String player) {
            int p = "0".equals(player) ? 0 : 1;
            int x = enc>>4, y = enc&0xF;
            int mx = x/3, my = y/3, ox = mx*3, oy = my*3;
            byte old = board[x][y];
            board[x][y] = (byte) p;
            byte bp = (byte) p;
            int threats = 0;
            for (int r=0;r<3;r++){int pc=0,ec=0;for(int c=0;c<3;c++){if(board[ox+r][oy+c]==bp)pc++;else if(board[ox+r][oy+c]==-1)ec++;}if(pc==2&&ec==1)threats++;}
            for (int c=0;c<3;c++){int pc=0,ec=0;for(int r=0;r<3;r++){if(board[ox+r][oy+c]==bp)pc++;else if(board[ox+r][oy+c]==-1)ec++;}if(pc==2&&ec==1)threats++;}
            {int pc=0,ec=0;for(int d=0;d<3;d++){if(board[ox+d][oy+d]==bp)pc++;else if(board[ox+d][oy+d]==-1)ec++;}if(pc==2&&ec==1)threats++;}
            {int pc=0,ec=0;for(int d=0;d<3;d++){if(board[ox+d][oy+2-d]==bp)pc++;else if(board[ox+d][oy+2-d]==-1)ec++;}if(pc==2&&ec==1)threats++;}
            board[x][y] = old;
            return threats;
        }

        // ---- Static evaluation ----

        int evaluate() {
            int score = 0;
            for (int mr=0;mr<3;mr++) for (int mc=0;mc<3;mc++) {
                int w = POS[mr*3+mc];
                if      (macro[mr][mc] == me)  score += 200 * w;
                else if (macro[mr][mc] == opp) score -= 200 * w;
                else if (macro[mr][mc] == AVAIL_CELL || macro[mr][mc] == EMPTY_CELL)
                    score += scoreMicro(mr, mc) * w;
            }
            score += macroLineScore();
            return score;
        }

        private int scoreMicro(int mr, int mc) {
            int ox = mr*3, oy = mc*3, score = 0;
            byte bme = (byte)me, bopp = (byte)opp;
            // rows
            for (int r=0;r<3;r++){int m=0,o=0;for(int c=0;c<3;c++){if(board[ox+r][oy+c]==bme)m++;else if(board[ox+r][oy+c]==bopp)o++;}if(o==0)score+=m==2?15:m*2;if(m==0)score-=o==2?15:o*2;}
            // cols
            for (int c=0;c<3;c++){int m=0,o=0;for(int r=0;r<3;r++){if(board[ox+r][oy+c]==bme)m++;else if(board[ox+r][oy+c]==bopp)o++;}if(o==0)score+=m==2?15:m*2;if(m==0)score-=o==2?15:o*2;}
            // diags
            {int m=0,o=0;for(int d=0;d<3;d++){if(board[ox+d][oy+d]==bme)m++;else if(board[ox+d][oy+d]==bopp)o++;}if(o==0)score+=m==2?15:m*2;if(m==0)score-=o==2?15:o*2;}
            {int m=0,o=0;for(int d=0;d<3;d++){if(board[ox+d][oy+2-d]==bme)m++;else if(board[ox+d][oy+2-d]==bopp)o++;}if(o==0)score+=m==2?15:m*2;if(m==0)score-=o==2?15:o*2;}
            return score;
        }

        private int macroLineScore() {
            int score = 0;
            byte bme = (byte)me, bopp = (byte)opp;
            for (int r=0;r<3;r++){int m=0,o=0,op=0;for(int c=0;c<3;c++){if(macro[r][c]==bme)m++;else if(macro[r][c]==bopp)o++;else if(macro[r][c]==AVAIL_CELL||macro[r][c]==EMPTY_CELL)op++;}if(o==0&&m+op==3)score+=m==2?600:m*40;if(m==0&&o+op==3)score-=o==2?600:o*40;}
            for (int c=0;c<3;c++){int m=0,o=0,op=0;for(int r=0;r<3;r++){if(macro[r][c]==bme)m++;else if(macro[r][c]==bopp)o++;else if(macro[r][c]==AVAIL_CELL||macro[r][c]==EMPTY_CELL)op++;}if(o==0&&m+op==3)score+=m==2?600:m*40;if(m==0&&o+op==3)score-=o==2?600:o*40;}
            {int m=0,o=0,op=0;for(int d=0;d<3;d++){if(macro[d][d]==bme)m++;else if(macro[d][d]==bopp)o++;else if(macro[d][d]==AVAIL_CELL||macro[d][d]==EMPTY_CELL)op++;}if(o==0&&m+op==3)score+=m==2?600:m*40;if(m==0&&o+op==3)score-=o==2?600:o*40;}
            {int m=0,o=0,op=0;for(int d=0;d<3;d++){if(macro[d][2-d]==bme)m++;else if(macro[d][2-d]==bopp)o++;else if(macro[d][2-d]==AVAIL_CELL||macro[d][2-d]==EMPTY_CELL)op++;}if(o==0&&m+op==3)score+=m==2?600:m*40;if(m==0&&o+op==3)score-=o==2?600:o*40;}
            return score;
        }

        // ---- Misc ----

        private boolean localFull(int mx, int my) {
            int ox=mx*3,oy=my*3;
            for(int r=0;r<3;r++) for(int c=0;c<3;c++) if(board[ox+r][oy+c]==-1) return false;
            return true;
        }

        private boolean allDecided() {
            for(int r=0;r<3;r++) for(int c=0;c<3;c++) if(macro[r][c]==AVAIL_CELL||macro[r][c]==EMPTY_CELL) return false;
            return true;
        }

        private int computeWinner() {
            for(int p=0;p<=1;p++) if(macroWin(p)) return p;
            if(allDecided()) return 2;
            return -2;
        }

        private long computeHash() {
            long h = 0;
            for(int i=0;i<9;i++) for(int j=0;j<9;j++) {
                int piece = board[i][j] == 0 ? 1 : board[i][j] == 1 ? 2 : 0;
                h ^= ZOB[i*9+j][piece];
            }
            return h;
        }
    }

    // =========================================================================
    // Original state generation (unchanged)
    // =========================================================================

    private void generateAllStates() {
        String[] values = {"-", "X", "O"};
        for (int i = 0; i < totalSize; i++) {
            int num = i;
            for (int j = 0; j < 9; j++) {
                allStates[i][j / 3][j % 3] = values[num % 3];
                num /= 3;
            }
        }
    }

    public static void main(String[] args) {
        ProtoBot b = new ProtoBot();
        for (int i = 0; i < 100; i++) printBoard(allStates[i]);
    }

    private static void printBoard(String[][] board) {
        for (String[] row : board) System.out.println(row[0] + row[1] + row[2]);
        System.out.println("-----");
    }
}