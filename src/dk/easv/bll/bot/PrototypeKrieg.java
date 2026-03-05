package dk.easv.bll.bot;

import dk.easv.bll.field.IField;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;

import java.util.List;

/**
 * PrototypeKrieg
 *
 * Minimax Ultimate Tic Tac Toe bot with:
 * - Alpha-beta pruning
 * - Precomputed 3^9 microboard state scoring
 * - Macroboard positional weighting
 *
 * BOT  = "0"
 * OPP  = "1"
 */
public class PrototypeKrieg implements IBot {

    static final String BOTNAME = "PrototypeKrieg";

    // Player identifiers used in board representation
    private static final String BOT = "0";
    private static final String OPPONENT = "1";

    // Total possible 3x3 board states (3^9)
    private static final int TOTAL_STATES = (int) Math.pow(3, 9);

    // All possible microboard states
    private static String[][][] allStates = new String[TOTAL_STATES][3][3];

    // Heuristic score for each microboard state
    private static int[] stateScores = new int[TOTAL_STATES];

    // Encoding values used when generating states
    private static final String[] VALUES = {" - ", " X ", " O "};

    /**
     * Constructor.
     * Precomputes all microboard states and their heuristic values.
     */
    public PrototypeKrieg() {
        generateAllStates();
        precomputeStateScores();
    }

    // -------------------------------------------------------------------------
    // Bot interface
    // -------------------------------------------------------------------------

    @Override
    public IMove doMove(IGameState state) {

        List<IMove> moves = state.getField().getAvailableMoves();
        if (moves.isEmpty()) return null;

        IMove bestMove = moves.get(0);
        int bestValue = Integer.MIN_VALUE;

        // Dynamic depth based on move count
        int depth = Math.min(6, moves.size());

        for (IMove move : moves) {

            makeMove(state, move, true);

            int value = minimax(state, depth - 1, false,
                    Integer.MIN_VALUE, Integer.MAX_VALUE);

            undoMove(state, move);

            if (value > bestValue) {
                bestValue = value;
                bestMove = move;
            }
        }

        return bestMove;
    }

    // -------------------------------------------------------------------------
    // Minimax with alpha-beta pruning
    // -------------------------------------------------------------------------

    private int minimax(IGameState state,
                        int depth,
                        boolean isMax,
                        int alpha,
                        int beta) {

        if (depth == 0 || isTerminal(state))
            return evaluate(state);

        List<IMove> moves = state.getField().getAvailableMoves();
        if (moves.isEmpty())
            return evaluate(state);

        if (isMax) {

            int maxEval = Integer.MIN_VALUE;

            for (IMove move : moves) {

                makeMove(state, move, true);
                int eval = minimax(state, depth - 1, false, alpha, beta);
                undoMove(state, move);

                maxEval = Math.max(maxEval, eval);
                alpha = Math.max(alpha, eval);

                if (beta <= alpha) break;
            }

            return maxEval;

        } else {

            int minEval = Integer.MAX_VALUE;

            for (IMove move : moves) {

                makeMove(state, move, false);
                int eval = minimax(state, depth - 1, true, alpha, beta);
                undoMove(state, move);

                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);

                if (beta <= alpha) break;
            }

            return minEval;
        }
    }

    // -------------------------------------------------------------------------
    // Evaluation
    // -------------------------------------------------------------------------

    /**
     * Evaluates full Ultimate Tic Tac Toe state.
     *
     * Priority:
     * 1. Macro win or loss.
     * 2. Macro pattern score.
     * 3. Microboard scores weighted by macro position.
     */
    private int evaluate(IGameState state) {

        String[][] macro = state.getField().getMacroboard();
        String[][] board = state.getField().getBoard();

        if (checkWin(macro, BOT)) return 100_000;
        if (checkWin(macro, OPPONENT)) return -100_000;

        int totalScore = 0;

        // Score macroboard structure
        int macroIndex = encodeMacroboard(macro);
        totalScore += stateScores[macroIndex] * 50;

        // Score each microboard
        for (int mi = 0; mi < 3; mi++) {
            for (int mj = 0; mj < 3; mj++) {

                if (macro[mi][mj].equals(BOT)) {
                    totalScore += 1000 * positionWeight(mi, mj);
                }
                else if (macro[mi][mj].equals(OPPONENT)) {
                    totalScore -= 1000 * positionWeight(mi, mj);
                }
                else {
                    int idx = encodeMicroboard(board, mi, mj);
                    totalScore += stateScores[idx] * positionWeight(mi, mj);
                }
            }
        }

        return totalScore;
    }

    // -------------------------------------------------------------------------
    // Terminal check
    // -------------------------------------------------------------------------

    private boolean isTerminal(IGameState state) {
        String[][] macro = state.getField().getMacroboard();
        return checkWin(macro, BOT)
                || checkWin(macro, OPPONENT)
                || state.getField().isFull();
    }

    // -------------------------------------------------------------------------
    // Win checks
    // -------------------------------------------------------------------------

    private boolean checkWin(String[][] board, String player) {

        for (int i = 0; i < 3; i++) {
            if (board[i][0].equals(player)
                    && board[i][1].equals(player)
                    && board[i][2].equals(player)) return true;

            if (board[0][i].equals(player)
                    && board[1][i].equals(player)
                    && board[2][i].equals(player)) return true;
        }

        if (board[0][0].equals(player)
                && board[1][1].equals(player)
                && board[2][2].equals(player)) return true;

        if (board[0][2].equals(player)
                && board[1][1].equals(player)
                && board[2][0].equals(player)) return true;

        return false;
    }

    /**
     * Checks win in a 3x3 microboard.
     */
    private boolean checkMicroWin(String[][] board,
                                  int microX,
                                  int microY,
                                  String player) {

        int sx = microX * 3;
        int sy = microY * 3;

        for (int i = 0; i < 3; i++) {

            if (board[sx+i][sy].equals(player)
                    && board[sx+i][sy+1].equals(player)
                    && board[sx+i][sy+2].equals(player)) return true;

            if (board[sx][sy+i].equals(player)
                    && board[sx+1][sy+i].equals(player)
                    && board[sx+2][sy+i].equals(player)) return true;
        }

        if (board[sx][sy].equals(player)
                && board[sx+1][sy+1].equals(player)
                && board[sx+2][sy+2].equals(player)) return true;

        if (board[sx][sy+2].equals(player)
                && board[sx+1][sy+1].equals(player)
                && board[sx+2][sy].equals(player)) return true;

        return false;
    }

    // -------------------------------------------------------------------------
    // Move application / undo
    // -------------------------------------------------------------------------

    private void makeMove(IGameState state,
                          IMove move,
                          boolean isBot) {

        String player = isBot ? BOT : OPPONENT;

        int x = move.getX();
        int y = move.getY();

        String[][] board = state.getField().getBoard();
        String[][] macro = state.getField().getMacroboard();

        board[x][y] = player;

        int microX = x / 3;
        int microY = y / 3;

        if (checkMicroWin(board, microX, microY, player))
            macro[microX][microY] = player;

        clearAvailable(macro);

        int nextX = x % 3;
        int nextY = y % 3;

        if (macro[nextX][nextY].equals(IField.EMPTY_FIELD)) {
            macro[nextX][nextY] = IField.AVAILABLE_FIELD;
        } else {
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    if (macro[i][j].equals(IField.EMPTY_FIELD))
                        macro[i][j] = IField.AVAILABLE_FIELD;
        }

        state.setMoveNumber(state.getMoveNumber() + 1);
    }

    private void undoMove(IGameState state, IMove move) {

        int x = move.getX();
        int y = move.getY();

        String[][] board = state.getField().getBoard();
        String[][] macro = state.getField().getMacroboard();

        board[x][y] = IField.EMPTY_FIELD;

        int microX = x / 3;
        int microY = y / 3;

        macro[microX][microY] = IField.EMPTY_FIELD;

        clearAvailable(macro);

        state.setMoveNumber(state.getMoveNumber() - 1);
    }

    private void clearAvailable(String[][] macro) {
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (macro[i][j].equals(IField.AVAILABLE_FIELD))
                    macro[i][j] = IField.EMPTY_FIELD;
    }

    // -------------------------------------------------------------------------
    // Encoding helpers
    // -------------------------------------------------------------------------

    private int encodeMicroboard(String[][] board, int mi, int mj) {

        int startX = mi * 3;
        int startY = mj * 3;

        int index = 0;
        int base = 1;

        for (int j = 0; j < 9; j++) {

            int row = j / 3;
            int col = j % 3;

            String cell = board[startX + row][startY + col];

            int digit = cell.equals(BOT) ? 1 :
                    cell.equals(OPPONENT) ? 2 : 0;

            index += digit * base;
            base *= 3;
        }

        return index;
    }

    private int encodeMacroboard(String[][] macro) {

        int index = 0;
        int base = 1;

        for (int j = 0; j < 9; j++) {

            int row = j / 3;
            int col = j % 3;

            String cell = macro[row][col];

            int digit = cell.equals(BOT) ? 1 :
                    cell.equals(OPPONENT) ? 2 : 0;

            index += digit * base;
            base *= 3;
        }

        return index;
    }

    // -------------------------------------------------------------------------
    // Precomputation
    // -------------------------------------------------------------------------

    /**
     * Generates all possible 3x3 board states.
     */
    private void generateAllStates() {

        for (int i = 0; i < TOTAL_STATES; i++) {

            int num = i;
            String[][] board = new String[3][3];

            for (int j = 0; j < 9; j++) {

                int valueIndex = num % 3;
                num /= 3;

                int row = j / 3;
                int col = j % 3;

                board[row][col] = VALUES[valueIndex];
            }

            allStates[i] = board;
        }
    }

    /**
     * Precomputes heuristic score for every microboard state.
     */
    private void precomputeStateScores() {

        int[][] lines = {
                {0,1,2},{3,4,5},{6,7,8},
                {0,3,6},{1,4,7},{2,5,8},
                {0,4,8},{2,4,6}
        };

        for (int i = 0; i < TOTAL_STATES; i++) {

            String[][] s = allStates[i];
            int score = 0;

            for (int[] line : lines) {

                String a = s[line[0]/3][line[0]%3];
                String b = s[line[1]/3][line[1]%3];
                String c = s[line[2]/3][line[2]%3];

                score += scoreLine(a, b, c);
            }

            stateScores[i] = score;
        }
    }

    /**
     * Scores one line of three cells.
     */
    private int scoreLine(String a, String b, String c) {

        int x = 0;
        int o = 0;

        for (String v : new String[]{a, b, c}) {
            if (v.equals(" X ")) x++;
            else if (v.equals(" O ")) o++;
        }

        if (x == 3) return 100;
        if (o == 3) return -100;
        if (x == 2 && o == 0) return 10;
        if (o == 2 && x == 0) return -10;

        return 0;
    }

    /**
     * Macro positional weight.
     * Center = 3
     * Corner = 2
     * Edge   = 1
     */
    private int positionWeight(int row, int col) {
        if (row == 1 && col == 1) return 3;
        if (row % 2 == 0 && col % 2 == 0) return 2;
        return 1;
    }

    @Override
    public String getBotName() {
        return BOTNAME;
    }
}