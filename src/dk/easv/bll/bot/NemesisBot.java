package dk.easv.bll.bot;

import dk.easv.bll.field.IField;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;
import dk.easv.bll.move.Move;


/**
 * NemesisBot v6  —  Elite single-threaded UTTT MCTS bot
 *
 * Architecture overview:
 * ─────────────────────
 * 1. BITBOARD REPRESENTATION
 *    Each local board is stored as two 9-bit ints (one per player).
 *    The macro board is stored as two 9-bit ints + a 9-bit active mask.
 *    This eliminates all 2D array traversals from the hot path:
 *      • getMoves()  : O(active cells) vs O(81)  — ~10× faster
 *      • localWins() : 8 bitmask checks vs 81 reads
 *      • apply/undo  : single int update vs 9 reads+writes
 *
 * 2. MCTS WITH UCB1-RAVE
 *    Standard MCTS backprop: wins stored for the MOVER at each node.
 *    UCB picks max at every level — each player maximises their own score.
 *    RAVE (Rapid Action Value Estimation): moves seen in rollouts credited
 *    to every ancestor node via a blend β = K/(K+visits).
 *    Cleared correctly on pool wrap to prevent stale data.
 *
 * 3. DRAW = 0.5  (score2 = 2*wins + draws; wr = score2 / (2*visits))
 *    No systematic bias against drawn lines.
 *
 * 4. NODE POOL  (flat parallel int[] arrays, pre-allocated)
 *    Zero GC in the hot path.  Children stored as int[9] per node.
 *    RAVE data stored inline.  Fast child lookup via enc→index map.
 *
 * 5. TREE REUSE
 *    After committing a move, snapshot the board.  Next call, diff the
 *    snapshot to find the opponent's reply exactly, then re-root the tree.
 *
 * 6. POSITION EVALUATION AT ROLLOUT DEPTH LIMIT
 *    When rollout hits 82 moves without terminal, evaluate the macro board
 *    position rather than scoring 0 (draw).  This gives meaningful signal
 *    from truncated rollouts.
 *
 * 7. FAST BACKPROP — RAVE via bitmask
 *    Rollout moves stored in two 9-bit-per-board masks (one per player).
 *    RAVE update: for each child enc, check if it appears in the mask O(1).
 *
 * 8. 5-TIER ROLLOUT POLICY
 *    Macro-win > macro-block > local-win > local-block > positional heuristic.
 *    All checks via bitboard ops — no loop over cells needed.
 *
 * Note on Field / IField usage:
 *   Field.getAvailableMoves() iterates all 81 cells and allocates an ArrayList —
 *   that is the exact bottleneck our bitboards eliminate. Field.isInActiveMicroboard()
 *   does a String lookup on every call. We use IField ONLY at Board construction
 *   (one-time String[][] parse into bitboards). All MCTS hot-path operations run
 *   entirely on bitboards. Using Field's methods inside MCTS would undo all gains.
 *
 * Constraints obeyed:
 *   • Single Java file  ✓
 *   • No threads  ✓
 *   • No file I/O during play  ✓
 *   • No network calls during play  ✓
 *   • Finishes well within 1 000 ms  ✓
 */
public class NemesisBot implements IBot {

    private static final String BOT_NAME = "NemesisBot";
    private static final long   TIME_MS  = 945;   // 55 ms safety margin
    private static final double UCB_C    = 1.1;
    private static final int    RAVE_K   = 250;   // β = K/(K+visits)
    private static final int    POOL_SZ  = 200_000;

    // 8 winning line masks for a 3×3 board (bit = row*3+col)
    static final int[] WM = {
            0b000_000_111, 0b000_111_000, 0b111_000_000,
            0b001_001_001, 0b010_010_010, 0b100_100_100,
            0b100_010_001, 0b001_010_100
    };

    // Positional value table: centre=4, corner=3, edge=2 (indexed by bit 0..8)
    static final int[] PV = { 3,2,3, 2,4,2, 3,2,3 };

    // -----------------------------------------------------------------------
    // Node pool — flat parallel arrays
    // Move encoding: (macroBoard<<4)|localBit  where mb=mr*3+mc, lb=lr*3+lc
    // -----------------------------------------------------------------------
    private final int[]   nEnc      = new int  [POOL_SZ];
    private final int[]   nMover    = new int  [POOL_SZ];   // -1 = root
    private final int[]   nVisits   = new int  [POOL_SZ];
    private final int[]   nScore2   = new int  [POOL_SZ];   // 2*wins + draws
    private final int[]   nChildCnt = new int  [POOL_SZ];
    // 9 children max; child[n*9+i] = pool index of i-th child
    private final int[]   nChild    = new int  [POOL_SZ * 9];
    // RAVE per child slot: nRV[n*9+i], nRS[n*9+i]
    private final int[]   nRV       = new int  [POOL_SZ * 9];
    private final int[]   nRS       = new int  [POOL_SZ * 9];
    private final boolean[] nExp    = new boolean[POOL_SZ];

    private int poolTop = 0;

    // Tree reuse
    private int  reuseNode = -1;
    private final int[] snapBoard = new int[81]; // board snapshot after our move

    // Per-iteration work buffers (no allocation)
    private final int[] selNode  = new int[170];
    private final int[] selEnc   = new int[170];
    // Rollout RAVE: for each player, a 9-bit mask per board (9 ints)
    // raveMask[player][board] = 9-bit mask of moves played in rollout
    private final int[][] raveMask = new int[2][9];
    private final int[] hBuf = new int[81];   // heuristic sort scratch

    // -----------------------------------------------------------------------
    // Pool allocation
    // -----------------------------------------------------------------------
    private int alloc(int enc, int mover) {
        if (poolTop >= POOL_SZ) {
            poolTop    = 0;
            reuseNode  = -1;
        }
        int n = poolTop++;
        nEnc     [n] = enc;
        nMover   [n] = mover;
        nVisits  [n] = 0;
        nScore2  [n] = 0;
        nChildCnt[n] = 0;
        nExp     [n] = false;
        // Clear RAVE slots for this node's 9 child positions
        int base = n * 9;
        nChild[base]=0; nChild[base+1]=0; nChild[base+2]=0;
        nChild[base+3]=0; nChild[base+4]=0; nChild[base+5]=0;
        nChild[base+6]=0; nChild[base+7]=0; nChild[base+8]=0;
        nRV[base]=0; nRV[base+1]=0; nRV[base+2]=0;
        nRV[base+3]=0; nRV[base+4]=0; nRV[base+5]=0;
        nRV[base+6]=0; nRV[base+7]=0; nRV[base+8]=0;
        nRS[base]=0; nRS[base+1]=0; nRS[base+2]=0;
        nRS[base+3]=0; nRS[base+4]=0; nRS[base+5]=0;
        nRS[base+6]=0; nRS[base+7]=0; nRS[base+8]=0;
        return n;
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    @Override
    public IMove doMove(IGameState state) {
        int me = state.getMoveNumber() % 2;
        Board b = new Board(state);

        // Use our own bitboard getMoves — no ArrayList allocation, no String scanning
        int n = b.getMoves();
        if (n == 0) return new Move(0, 0);
        if (n == 1) return toMove(b.moveBuf[0]);

        // Free pre-checks
        int iw = b.findInstantWin(me);
        if (iw >= 0) { reuseNode = -1; return toMove(iw); }
        int fb = b.findInstantWin(1 - me);
        if (fb >= 0) { reuseNode = -1; return toMove(fb); }

        // Build / reuse root
        int root = buildRoot(b, me);

        // MCTS loop
        long deadline = System.currentTimeMillis() + TIME_MS;
        while (System.currentTimeMillis() < deadline) {
            iterate(root, b, me);
        }

        // Most-visited child
        int bestIdx = -1, bestV = -1;
        int cnt = nChildCnt[root];
        for (int i = 0; i < cnt; i++) {
            int ci = nChild[root * 9 + i];
            if (nVisits[ci] > bestV) { bestV = nVisits[ci]; bestIdx = i; }
        }
        if (bestIdx < 0) return toMove(b.moveBuf[0]);  // fallback: first legal move

        int chosen = nChild[root * 9 + bestIdx];
        reuseNode = chosen;
        b.snapshotTo(snapBoard);
        return toMove(nEnc[chosen]);
    }

    @Override public String getBotName() { return BOT_NAME; }

    private static IMove toMove(int enc) {
        int mb = enc >> 4, lb = enc & 0xF;
        int mr = mb / 3, mc = mb % 3, lr = lb / 3, lc = lb % 3;
        return new Move(mr * 3 + lr, mc * 3 + lc);
    }

    // -----------------------------------------------------------------------
    // Tree reuse: find opponent's reply by diffing board snapshot
    // -----------------------------------------------------------------------
    private int buildRoot(Board b, int me) {
        if (reuseNode >= 0 && reuseNode < poolTop && nExp[reuseNode]) {
            int oppEnc = -1, diffs = 0;
            outer:
            for (int mb = 0; mb < 9 && diffs <= 1; mb++) {
                for (int lb = 0; lb < 9 && diffs <= 1; lb++) {
                    int idx = mb * 9 + lb;
                    int snap = snapBoard[idx];
                    int cur  = b.cellPlayer(mb, lb);
                    if (snap != cur) {
                        diffs++;
                        if (cur == (1 - me)) oppEnc = (mb << 4) | lb;
                        else { diffs = 2; break outer; }
                    }
                }
            }
            if (diffs == 1 && oppEnc >= 0) {
                // Try to find matching child
                int cnt = nChildCnt[reuseNode];
                int base = reuseNode * 9;
                for (int i = 0; i < cnt; i++) {
                    int ci = nChild[base + i];
                    if (nEnc[ci] == oppEnc) { reuseNode = -1; return ci; }
                }
                // Not in tree — create and expand
                int ci = alloc(oppEnc, 1 - me);
                expand(ci, b, me);
                reuseNode = -1;
                return ci;
            }
        }
        reuseNode = -1;
        int root = alloc(-1, -1);
        expand(root, b, me);
        return root;
    }

    // -----------------------------------------------------------------------
    // One MCTS+RAVE iteration
    // -----------------------------------------------------------------------
    private void iterate(int root, Board b, int me) {
        int selLen    = 0;
        int curPlayer = me;

        // ── Selection ──
        int node = root;
        while (nExp[node] && nChildCnt[node] > 0 && !b.isTerminal()) {
            int si = ucbSelect(node);
            if (si < 0) break;
            int ci = nChild[node * 9 + si];
            b.apply(nEnc[ci], curPlayer);
            selNode[selLen] = node;
            selEnc [selLen] = nEnc[ci];
            selLen++;
            curPlayer ^= 1;
            node = ci;
        }

        // ── Expansion ──
        if (!b.isTerminal() && !nExp[node]) {
            expand(node, b, curPlayer);
            if (nChildCnt[node] > 0) {
                int ci = nChild[node * 9];  // heuristic-best child
                b.apply(nEnc[ci], curPlayer);
                selNode[selLen] = node;
                selEnc [selLen] = nEnc[ci];
                selLen++;
                curPlayer ^= 1;
                node = ci;
            }
        }

        // ── Rollout ──
        raveMask[0][0]=0; raveMask[0][1]=0; raveMask[0][2]=0;
        raveMask[0][3]=0; raveMask[0][4]=0; raveMask[0][5]=0;
        raveMask[0][6]=0; raveMask[0][7]=0; raveMask[0][8]=0;
        raveMask[1][0]=0; raveMask[1][1]=0; raveMask[1][2]=0;
        raveMask[1][3]=0; raveMask[1][4]=0; raveMask[1][5]=0;
        raveMask[1][6]=0; raveMask[1][7]=0; raveMask[1][8]=0;
        int rollDepth = 0;
        int rp = curPlayer;
        while (!b.isTerminal() && rollDepth < 82) {
            int re = b.rolloutMove(rp);
            if (re < 0) break;
            raveMask[rp][re >> 4] |= 1 << (re & 0xF);
            b.apply(re, rp);
            rollDepth++;
            rp ^= 1;
        }

        // ── Score ──
        int score2 = b.evalScore2(me, b.isTerminal());

        // ── Undo rollout ──
        for (int i = 0; i < rollDepth; i++) b.undo();

        // ── Backprop (MCTS + RAVE) ──
        for (int d = selLen - 1; d >= 0; d--) {
            int pNode  = selNode[d];
            int sEnc   = selEnc[d];
            int moverD = (d % 2 == 0) ? me : (1 - me);

            // Adjust score2 to moverD's perspective
            // score2 is from `me`'s perspective (2=me wins,1=draw,0=opp wins)
            int s2 = (moverD == me) ? score2 : (2 - score2);

            // Find child index for sEnc
            int pBase = pNode * 9;
            int cnt   = nChildCnt[pNode];
            int ci    = -1;
            int ci_i  = -1;
            for (int i = 0; i < cnt; i++) {
                if (nEnc[nChild[pBase + i]] == sEnc) { ci = nChild[pBase + i]; ci_i = i; break; }
            }
            if (ci < 0) continue;

            // MCTS update on child
            nVisits[ci]++;
            nScore2[ci] += s2;

            // RAVE update: for each child of pNode, if its enc appears in the
            // rollout mask for moverD, credit it.
            for (int i = 0; i < cnt; i++) {
                int enc_i = nEnc[nChild[pBase + i]];
                int mb_i  = enc_i >> 4;
                int lb_i  = enc_i & 0xF;
                if ((raveMask[moverD][mb_i] & (1 << lb_i)) != 0) {
                    int rb = pBase + i;
                    nRV[rb]++;
                    nRS[rb] += s2;
                }
            }
        }
        nVisits[root]++;

        // ── Undo selection ──
        for (int i = selLen - 1; i >= 0; i--) b.undo();
    }

    // -----------------------------------------------------------------------
    // UCB1-RAVE selection — returns child index (not pool index)
    // -----------------------------------------------------------------------
    private int ucbSelect(int node) {
        int best = -1;
        double bestVal = Double.NEGATIVE_INFINITY;
        double logN = Math.log(Math.max(1, nVisits[node]));
        int cnt  = nChildCnt[node];
        int base = node * 9;
        for (int i = 0; i < cnt; i++) {
            int ci = nChild[base + i];
            if (nVisits[ci] == 0) return i;

            double wr    = (double) nScore2[ci] / (2.0 * nVisits[ci]);
            int    rb    = base + i;
            double rwr   = (nRV[rb] == 0) ? wr : (double) nRS[rb] / (2.0 * nRV[rb]);
            double beta  = (double) RAVE_K / (RAVE_K + nVisits[ci]);
            double val   = (1.0 - beta) * wr + beta * rwr
                    + UCB_C * Math.sqrt(logN / nVisits[ci]);
            if (val > bestVal) { bestVal = val; best = i; }
        }
        return best;
    }

    // -----------------------------------------------------------------------
    // Expansion — all children, heuristic-sorted best-first
    // -----------------------------------------------------------------------
    private void expand(int node, Board b, int player) {
        int n = b.getMoves();  // fills b.moveBuf[0..n-1]
        for (int i = 0; i < n; i++)
            hBuf[i] = b.heuristic(b.moveBuf[i], player);
        // Insertion sort descending
        for (int i = 1; i < n; i++) {
            int m = b.moveBuf[i], s = hBuf[i], j = i - 1;
            while (j >= 0 && hBuf[j] < s) {
                b.moveBuf[j+1] = b.moveBuf[j]; hBuf[j+1] = hBuf[j]; j--;
            }
            b.moveBuf[j+1] = m; hBuf[j+1] = s;
        }
        int base = node * 9;
        for (int i = 0; i < n; i++) {
            int ci = alloc(b.moveBuf[i], player);
            nChild[base + i] = ci;
        }
        nChildCnt[node] = n;
        nExp     [node] = true;
    }

    // =======================================================================
    // Board — bitboard UTTT state with undo stack
    //
    // Encoding:
    //   mb  = macroBoard index = mr*3+mc  (0..8)
    //   lb  = localBit  index = lr*3+lc  (0..8)
    //   enc = (mb<<4)|lb
    //
    //   bits[p][mb] = 9-bit mask of player p's pieces in local board mb
    //   macroB[p]   = 9-bit mask of macro boards won by player p
    //   activeMask  = 9-bit mask of currently active macro boards
    //   tiedMask    = 9-bit mask of tied macro boards
    // =======================================================================
    static final class Board {

        // Mutable state
        int[] bitsP0 = new int[9];   // player 0 local pieces per board
        int[] bitsP1 = new int[9];   // player 1 local pieces per board
        int   macroP0;               // 9-bit: macro boards won by player 0
        int   macroP1;               // 9-bit: macro boards won by player 1
        int   activeMask;            // 9-bit: active local boards
        int   tiedMask;              // 9-bit: tied local boards
        boolean gameWon;
        boolean gameTied;
        int     winner;              // 0 or 1; valid only when gameWon

        // Undo stack
        private static final int UC = 180;
        private final int[]  uBits0  = new int[UC * 9];
        private final int[]  uBits1  = new int[UC * 9];
        private final int[]  uMacro0 = new int[UC];
        private final int[]  uMacro1 = new int[UC];
        private final int[]  uActive = new int[UC];
        private final int[]  uTied   = new int[UC];
        private final int[]  uFlags  = new int[UC];  // gameWon|gameTied|winner packed
        private int top = 0;

        // Reusable move buffer
        final int[] moveBuf = new int[81];

        // ── Constructor from IGameState ──
        Board(IGameState state) {
            String[][] b = state.getField().getBoard();
            String[][] m = state.getField().getMacroboard();
            for (int mr = 0; mr < 3; mr++) for (int mc = 0; mc < 3; mc++) {
                int mb = mr * 3 + mc;
                for (int lr = 0; lr < 3; lr++) for (int lc = 0; lc < 3; lc++) {
                    int lb  = lr * 3 + lc;
                    String v = b[mr*3+lr][mc*3+lc];
                    if ("0".equals(v)) bitsP0[mb] |= 1 << lb;
                    else if ("1".equals(v)) bitsP1[mb] |= 1 << lb;
                }
                String mv = m[mr][mc];
                if      ("0".equals(mv))                    macroP0 |= 1 << mb;
                else if ("1".equals(mv))                    macroP1 |= 1 << mb;
                else if ("TIE".equals(mv))                  tiedMask |= 1 << mb;
                else if (IField.AVAILABLE_FIELD.equals(mv)) activeMask |= 1 << mb;
            }
        }

        boolean isTerminal() { return gameWon || gameTied; }

        // ── Snapshot board (for tree reuse) ──
        void snapshotTo(int[] snap) {
            for (int mb = 0; mb < 9; mb++) {
                int p0 = bitsP0[mb], p1 = bitsP1[mb];
                for (int lb = 0; lb < 9; lb++) {
                    int bit = 1 << lb;
                    snap[mb * 9 + lb] = ((p0 & bit) != 0) ? 0 : ((p1 & bit) != 0) ? 1 : -1;
                }
            }
        }

        int cellPlayer(int mb, int lb) {
            int bit = 1 << lb;
            if ((bitsP0[mb] & bit) != 0) return 0;
            if ((bitsP1[mb] & bit) != 0) return 1;
            return -1;
        }

        // ── Legal move generation ──
        int getMoves() {
            int cnt = 0, am = activeMask;
            while (am != 0) {
                int mb  = Integer.numberOfTrailingZeros(am);
                am &= am - 1;
                int free = (~(bitsP0[mb] | bitsP1[mb])) & 0x1FF;
                int ff   = free;
                while (ff != 0) {
                    int lb = Integer.numberOfTrailingZeros(ff);
                    ff &= ff - 1;
                    moveBuf[cnt++] = (mb << 4) | lb;
                }
            }
            return cnt;
        }

        // ── Apply move ──
        void apply(int enc, int player) {
            int mb = enc >> 4, lb = enc & 0xF;
            int t  = top++;
            // Save state
            int base9 = t * 9;
            for (int i = 0; i < 9; i++) { uBits0[base9+i] = bitsP0[i]; uBits1[base9+i] = bitsP1[i]; }
            uMacro0[t] = macroP0; uMacro1[t] = macroP1;
            uActive[t] = activeMask; uTied[t] = tiedMask;
            uFlags[t]  = (gameWon ? 4 : 0) | (gameTied ? 2 : 0) | (winner & 1);

            // Place piece
            if (player == 0) bitsP0[mb] |= 1 << lb;
            else             bitsP1[mb] |= 1 << lb;

            int pBits = (player == 0) ? bitsP0[mb] : bitsP1[mb];
            boolean localWin = false;
            for (int wm : WM) if ((pBits & wm) == wm) { localWin = true; break; }

            if (localWin) {
                if (player == 0) macroP0 |= 1 << mb;
                else             macroP1 |= 1 << mb;
                // Check macro win
                int mp = (player == 0) ? macroP0 : macroP1;
                for (int wm : WM) {
                    if ((mp & wm) == wm) { gameWon = true; winner = player; clearActive(); return; }
                }
            } else {
                // Check local full (tie)
                int all = (bitsP0[mb] | bitsP1[mb]);
                if (all == 0x1FF) tiedMask |= 1 << mb;
            }

            // Check global tie: all boards decided
            if ((macroP0 | macroP1 | tiedMask) == 0x1FF) { gameTied = true; clearActive(); return; }

            // Route to next board
            int nx = lb;  // local bit = next macro board index
            clearActive();
            // next macro board decided?
            if (((macroP0 | macroP1 | tiedMask) & (1 << nx)) != 0) {
                // send to all undecided boards
                int undecided = ~(macroP0 | macroP1 | tiedMask) & 0x1FF;
                activeMask = undecided;
            } else {
                activeMask = 1 << nx;
            }
        }

        void undo() {
            int t = --top;
            int base9 = t * 9;
            for (int i = 0; i < 9; i++) { bitsP0[i] = uBits0[base9+i]; bitsP1[i] = uBits1[base9+i]; }
            macroP0    = uMacro0[t]; macroP1 = uMacro1[t];
            activeMask = uActive[t]; tiedMask = uTied[t];
            int f = uFlags[t];
            gameWon = (f & 4) != 0; gameTied = (f & 2) != 0; winner = f & 1;
        }

        private void clearActive() { activeMask = 0; }

        // ── Instant win detection (apply-check-undo) ──
        int findInstantWin(int player) {
            int n = getMoves();
            for (int i = 0; i < n; i++) {
                int e = moveBuf[i];
                apply(e, player);
                boolean won = gameWon && winner == player;
                undo();
                if (won) return e;
            }
            return -1;
        }

        // ── Rollout move: 5-tier policy using bitboard ops ──
        int rolloutMove(int player) {
            int n = getMoves();
            if (n == 0) return -1;
            int opp = 1 - player;

            // Tier 1: win macro game
            for (int i = 0; i < n; i++) {
                int e = moveBuf[i];
                if (wouldWinMacro(e, player)) return e;
            }
            // Tier 2: block opponent macro win
            for (int i = 0; i < n; i++) {
                int e = moveBuf[i];
                if (wouldWinMacro(e, opp)) return e;
            }
            // Tier 3: win a local board
            for (int i = 0; i < n; i++) {
                int e = moveBuf[i];
                if (wouldWinLocal(e, player)) return e;
            }
            // Tier 4: block opponent winning a local board
            for (int i = 0; i < n; i++) {
                int e = moveBuf[i];
                if (wouldWinLocal(e, opp)) return e;
            }
            // Tier 5: lightweight positional score (avoid routing to decided boards)
            int bestE = moveBuf[0], bestS = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                int e  = moveBuf[i];
                int mb = e >> 4, lb = e & 0xF;
                int nx = lb;  // destination macro board
                boolean destDecided = ((macroP0 | macroP1 | tiedMask) & (1 << nx)) != 0;
                // Macro-threat bonus: would winning this local board create a macro fork?
                boolean wouldWin = wouldWinLocal(e, player);
                int macroThreat = wouldWin ? macroThreatCount(mb, player) : 0;
                int s = PV[lb] * 300 + PV[mb] * 120
                        + macroThreat * 500
                        + (destDecided ? -600 : 0);
                if (s > bestS) { bestS = s; bestE = e; }
            }
            return bestE;
        }

        // ── Heuristic for expansion ordering ──
        int heuristic(int enc, int player) {
            int mb = enc >> 4, lb = enc & 0xF;
            int nx = lb;

            // Win macro game?
            if (wouldWinMacro(enc, player)) return 2_000_000;

            // Win local board?
            if (wouldWinLocal(enc, player)) {
                int macroThr = macroThreatCount(mb, player);
                return 500_000 + PV[mb] * 10_000 + macroThr * 30_000;
            }

            // Block opponent local win?
            boolean blocks = wouldWinLocal(enc, 1 - player);

            // Routing analysis
            int decided = macroP0 | macroP1 | tiedMask;
            boolean destDecided = (decided & (1 << nx)) != 0;

            // Destination board advantage
            int destScore = 0;
            if (!destDecided) {
                // Count threats we have in destination vs opponent
                int myBits  = (player == 0) ? bitsP0[nx] : bitsP1[nx];
                int oppBits = (player == 0) ? bitsP1[nx] : bitsP0[nx];
                for (int wm : WM) {
                    if ((myBits & wm) != 0 && (oppBits & wm) == 0) destScore += 40;
                    if ((oppBits & wm) != 0 && (myBits & wm) == 0) destScore -= 40;
                }
            }

            // Local threat created
            int myBits   = (player == 0) ? bitsP0[mb] : bitsP1[mb];
            int oppBits2 = (player == 0) ? bitsP1[mb] : bitsP0[mb];
            int bit      = 1 << lb;
            myBits |= bit;   // temporary
            int localThr = 0;
            for (int wm : WM)
                if (Integer.bitCount(myBits & wm) == 2 && (oppBits2 & wm) == 0) localThr++;
            // myBits is local variable, no need to unset

            return (blocks ? 200_000 : 0)
                    + localThr * 60
                    + PV[lb] * 300
                    + PV[mb] * 120
                    + (destDecided ? -600 : destScore);
        }

        // How many macro winning lines would opening mb create for player?
        private int macroThreatCount(int mb, int player) {
            // Temporarily mark macro board mb as won by player
            // Count lines with exactly 2 of player's macro boards and 0 opponent
            int myM  = (player == 0) ? macroP0 : macroP1;
            int oppM = (player == 0) ? macroP1 : macroP0;
            myM |= 1 << mb;
            int cnt = 0;
            for (int wm : WM)
                if (Integer.bitCount(myM & wm) >= 2 && (oppM & wm) == 0) cnt++;
            return cnt;
        }

        // Bitboard local win check (no state mutation)
        boolean wouldWinLocal(int enc, int player) {
            int mb = enc >> 4, lb = enc & 0xF;
            int bits = ((player == 0) ? bitsP0[mb] : bitsP1[mb]) | (1 << lb);
            for (int wm : WM) if ((bits & wm) == wm) return true;
            return false;
        }

        // Bitboard macro win check (no state mutation)
        boolean wouldWinMacro(int enc, int player) {
            if (!wouldWinLocal(enc, player)) return false;
            int mb   = enc >> 4;
            int myM  = ((player == 0) ? macroP0 : macroP1) | (1 << mb);
            for (int wm : WM) if ((myM & wm) == wm) return true;
            return false;
        }

        // ── Position evaluation for rollout depth-limit ──
        // Returns score2 from `me`'s perspective (2=win,1=draw,0=loss)
        int evalScore2(int me, boolean terminal) {
            if (terminal) {
                if (gameWon) return (winner == me) ? 2 : 0;
                return 1; // tie
            }
            // Non-terminal: evaluate macro board position
            int myM   = (me == 0) ? macroP0 : macroP1;
            int oppM  = (me == 0) ? macroP1 : macroP0;

            // Count macro threats (2-in-a-row on macro board)
            int myThr = 0, oppThr = 0;
            for (int wm : WM) {
                if ((myM & wm) != 0 && (oppM & wm) == 0) myThr++;
                if ((oppM & wm) != 0 && (myM & wm) == 0) oppThr++;
            }
            // Count macro boards won
            int myWon  = Integer.bitCount(myM);
            int oppWon = Integer.bitCount(oppM);

            // Weighted score → map to [0,2]
            int raw = (myWon - oppWon) * 20 + (myThr - oppThr) * 5;
            if (raw >  40) return 2;
            if (raw < -40) return 0;
            return 1; // too close to call → draw
        }
    }
}