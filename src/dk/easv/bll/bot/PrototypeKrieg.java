package dk.easv.bll.bot;

import dk.easv.bll.field.IField;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;

import java.util.List;

public class PrototypeKrieg implements IBot {
    static final String BOTNAME = "ProtoKrieg";

    static private String[][][] allStates;
    static private int[] stateScores;

    private final int totalSize = (int) Math.pow(3, 9);

    private static final String[] VALUES = {" - ", " X ", " O "};
    private static final String BOT      = "0";
    private static final String OPPONENT = "1";

    // Thrown to abort minimax when the deadline is exceeded
    private static class TimeoutException extends RuntimeException {
        TimeoutException() { super(null, null, true, false); }
    }

    public PrototypeKrieg() {
        allStates   = new String[totalSize][3][3];
        stateScores = new int[totalSize];
        generateAllStates();
        precomputeStateScores();
    }

    // -------------------------------------------------------------------------
    // Public interface
    // -------------------------------------------------------------------------

    @Override
    public IMove doMove(IGameState state) {
        List<IMove> moves = state.getField().getAvailableMoves();
        if (moves.isEmpty()) return null;

        // Detect free-choice turn: every non-won microboard is available
        boolean freeChoice = isFreeChoiceTurn(state);

        if (freeChoice) {
            // Use iterative deepening with a hard 200 ms wall-clock limit
            return doMoveTimeLimited(state, moves, 200L);
        } else {
            // Normal turn – fixed depth search as before
            return doMoveFixedDepth(state, moves, Math.min(6, moves.size()));
        }
    }

    /**
     * Iterative-deepening search that stops as soon as the deadline is reached.
     * Always returns the best complete result from the last fully-searched depth.
     */
    private IMove doMoveTimeLimited(IGameState state, List<IMove> moves, long timeLimitMs) {
        long deadline = System.currentTimeMillis() + timeLimitMs;

        IMove bestMove  = moves.get(0);
        int   bestValue = Integer.MIN_VALUE;

        // Seed with a fast depth-1 result so we always have something valid
        for (IMove move : moves) {
            makeMove(state, move, true);
            int value = evaluate(state);
            undoMove(state, move);
            if (value > bestValue) {
                bestValue = value;
                bestMove  = move;
            }
        }

        // Iterative deepening: keep going deeper until time runs out
        for (int depth = 2; depth <= 9; depth++) {
            IMove  candidateBest  = bestMove;
            int    candidateValue = Integer.MIN_VALUE;
            boolean completedDepth = true;

            for (IMove move : moves) {
                makeMove(state, move, true);
                try {
                    int value = minimax(state, depth - 1, false,
                            Integer.MIN_VALUE, Integer.MAX_VALUE, deadline);
                    undoMove(state, move);

                    if (value > candidateValue ||
                            (value == candidateValue && Math.random() < 0.3)) {
                        candidateValue = value;
                        candidateBest  = move;
                    }
                } catch (TimeoutException e) {
                    undoMove(state, move);
                    completedDepth = false;
                    break;
                }
            }

            if (completedDepth) {
                // Full depth was searched – commit this result
                bestMove  = candidateBest;
                bestValue = candidateValue;
            }
            // Whether or not we finished, stop if time is up
            if (System.currentTimeMillis() >= deadline) break;
        }

        return bestMove;
    }

    /** Original fixed-depth search used for constrained turns. */
    private IMove doMoveFixedDepth(IGameState state, List<IMove> moves, int depth) {
        IMove bestMove  = moves.get(0);
        int   bestValue = Integer.MIN_VALUE;

        for (IMove move : moves) {
            makeMove(state, move, true);
            int value = minimax(state, depth - 1, false,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);
            undoMove(state, move);

            if (value > bestValue || (value == bestValue && Math.random() < 0.3)) {
                bestValue = value;
                bestMove  = move;
            }
        }
        return bestMove;
    }

    /**
     * Returns true when it is a "free choice" turn, i.e. every microboard that
     * has not been won/drawn is marked as available (the bot may play anywhere).
     */
    private boolean isFreeChoiceTurn(IGameState state) {
        String[][] macro = state.getField().getMacroboard();
        int availableCount = 0;
        int openCount      = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                String cell = macro[i][j];
                if (cell.equals(IField.AVAILABLE_FIELD)) availableCount++;
                if (cell.equals(IField.EMPTY_FIELD) || cell.equals(IField.AVAILABLE_FIELD)) openCount++;
            }
        }
        // Free choice: every open microboard is currently available
        return openCount > 0 && availableCount == openCount;
    }

    // -------------------------------------------------------------------------
    // Minimax with alpha-beta pruning + optional deadline
    // -------------------------------------------------------------------------

    private int minimax(IGameState state, int depth, boolean isMax,
                        int alpha, int beta, long deadline) {

        if (System.currentTimeMillis() >= deadline) throw new TimeoutException();

        if (depth == 0 || isTerminal(state)) return evaluate(state);

        List<IMove> moves = state.getField().getAvailableMoves();
        if (moves.isEmpty()) return evaluate(state);

        if (isMax) {
            int maxEval = Integer.MIN_VALUE;
            for (IMove move : moves) {
                makeMove(state, move, true);
                int eval = minimax(state, depth - 1, false, alpha, beta, deadline);
                undoMove(state, move);
                maxEval = Math.max(maxEval, eval);
                alpha   = Math.max(alpha, eval);
                if (beta <= alpha) break;
            }
            return maxEval;
        } else {
            int minEval = Integer.MAX_VALUE;
            for (IMove move : moves) {
                makeMove(state, move, false);
                int eval = minimax(state, depth - 1, true, alpha, beta, deadline);
                undoMove(state, move);
                minEval = Math.min(minEval, eval);
                beta    = Math.min(beta, eval);
                if (beta <= alpha) break;
            }
            return minEval;
        }
    }

    // -------------------------------------------------------------------------
    // Evaluation using pre-computed state table
    // -------------------------------------------------------------------------

    private int evaluate(IGameState state) {
        String[][] macro = state.getField().getMacroboard();
        String[][] board = state.getField().getBoard();

        if (checkWin(macro, BOT))      return  100_000;
        if (checkWin(macro, OPPONENT)) return -100_000;

        int totalScore = 0;

        int macroIndex = encodeMacroboard(macro);
        totalScore += stateScores[macroIndex] * 50;

        for (int mi = 0; mi < 3; mi++) {
            for (int mj = 0; mj < 3; mj++) {
                String cell = macro[mi][mj];
                if (cell.equals(BOT)) {
                    totalScore += 1_000 * positionWeight(mi, mj);
                } else if (cell.equals(OPPONENT)) {
                    totalScore -= 1_000 * positionWeight(mi, mj);
                } else {
                    int idx = encodeMicroboard(board, mi, mj);
                    totalScore += stateScores[idx] * positionWeight(mi, mj);
                }
            }
        }

        return totalScore;
    }

    // -------------------------------------------------------------------------
    // State table helpers
    // -------------------------------------------------------------------------

    private int encodeMicroboard(String[][] board, int mi, int mj) {
        int startX = mi * 3;
        int startY = mj * 3;
        int index  = 0;
        int base   = 1;
        for (int j = 0; j < 9; j++) {
            int row = j / 3;
            int col = j % 3;
            String cell = board[startX + row][startY + col];
            int digit = cellToDigit(cell);
            index += digit * base;
            base  *= 3;
        }
        return index;
    }

    private int encodeMacroboard(String[][] macro) {
        int index = 0;
        int base  = 1;
        for (int j = 0; j < 9; j++) {
            int row  = j / 3;
            int col  = j % 3;
            String cell = macro[row][col];
            int digit;
            if      (cell.equals(BOT))      digit = 1;
            else if (cell.equals(OPPONENT)) digit = 2;
            else                            digit = 0;
            index += digit * base;
            base  *= 3;
        }
        return index;
    }

    private int cellToDigit(String cell) {
        if (cell.equals(BOT))      return 1;
        if (cell.equals(OPPONENT)) return 2;
        return 0;
    }

    private void precomputeStateScores() {
        int[][] lines = {
                {0,1,2},{3,4,5},{6,7,8},
                {0,3,6},{1,4,7},{2,5,8},
                {0,4,8},{2,4,6}
        };

        for (int i = 0; i < totalSize; i++) {
            String[][] s = allStates[i];
            int score = 0;

            for (int[] line : lines) {
                int r0 = line[0]/3, c0 = line[0]%3;
                int r1 = line[1]/3, c1 = line[1]%3;
                int r2 = line[2]/3, c2 = line[2]%3;
                String a = s[r0][c0], b = s[r1][c1], c = s[r2][c2];
                score += scoreLineAllStates(a, b, c);
            }

            if      (s[1][1].equals(" X ")) score += 3;
            else if (s[1][1].equals(" O ")) score -= 3;

            int[][] corners = {{0,0},{0,2},{2,0},{2,2}};
            for (int[] corner : corners) {
                if      (s[corner[0]][corner[1]].equals(" X ")) score += 2;
                else if (s[corner[0]][corner[1]].equals(" O ")) score -= 2;
            }

            stateScores[i] = score;
        }
    }

    private int scoreLineAllStates(String a, String b, String c) {
        int xCount = 0, oCount = 0;
        for (String v : new String[]{a, b, c}) {
            if (v.equals(" X ")) xCount++;
            else if (v.equals(" O ")) oCount++;
        }
        if (xCount == 3)                return  100;
        if (oCount == 3)                return -100;
        if (xCount == 2 && oCount == 0) return   10;
        if (oCount == 2 && xCount == 0) return  -10;
        return 0;
    }

    private int positionWeight(int row, int col) {
        if (row == 1 && col == 1) return 3;
        if (row % 2 == 0 && col % 2 == 0) return 2;
        return 1;
    }

    // -------------------------------------------------------------------------
    // Move application / undo
    // -------------------------------------------------------------------------

    private void makeMove(IGameState state, IMove move, boolean isBot) {
        String player = isBot ? BOT : OPPONENT;
        int x = move.getX();
        int y = move.getY();

        String[][] board = state.getField().getBoard();
        String[][] macro = state.getField().getMacroboard();

        board[x][y] = player;

        int microX = x / 3;
        int microY = y / 3;
        if (checkMicroWin(board, microX, microY, player)) {
            macro[microX][microY] = player;
        }

        int nextMicroX = x % 3;
        int nextMicroY = y % 3;

        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (macro[i][j].equals(IField.AVAILABLE_FIELD))
                    macro[i][j] = IField.EMPTY_FIELD;

        if (macro[nextMicroX][nextMicroY].equals(IField.EMPTY_FIELD)) {
            macro[nextMicroX][nextMicroY] = IField.AVAILABLE_FIELD;
        } else {
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    if (macro[i][j].equals(IField.EMPTY_FIELD))
                        macro[i][j] = IField.AVAILABLE_FIELD;
        }

        state.setMoveNumber(state.getMoveNumber() + 1);
    }

    private void undoMove(IGameState state, IMove move) {
        String[][] board = state.getField().getBoard();
        String[][] macro = state.getField().getMacroboard();

        int x = move.getX();
        int y = move.getY();
        board[x][y] = IField.EMPTY_FIELD;

        int microX = x / 3;
        int microY = y / 3;
        if (!checkMicroWin(board, microX, microY, BOT) &&
                !checkMicroWin(board, microX, microY, OPPONENT)) {
            macro[microX][microY] = IField.EMPTY_FIELD;
        }

        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (macro[i][j].equals(IField.AVAILABLE_FIELD))
                    macro[i][j] = IField.EMPTY_FIELD;

        int lastMicroX = x % 3;
        int lastMicroY = y % 3;
        if (macro[lastMicroX][lastMicroY].equals(IField.EMPTY_FIELD)) {
            macro[lastMicroX][lastMicroY] = IField.AVAILABLE_FIELD;
        } else {
            for (int i = 0; i < 3; i++)
                for (int j = 0; j < 3; j++)
                    if (macro[i][j].equals(IField.EMPTY_FIELD))
                        macro[i][j] = IField.AVAILABLE_FIELD;
        }

        state.setMoveNumber(state.getMoveNumber() - 1);
    }

    // -------------------------------------------------------------------------
    // Win / terminal checks
    // -------------------------------------------------------------------------

    private boolean isTerminal(IGameState state) {
        String[][] macro = state.getField().getMacroboard();
        return checkWin(macro, BOT) || checkWin(macro, OPPONENT) || state.getField().isFull();
    }

    private boolean checkWin(String[][] board, String player) {
        for (int i = 0; i < 3; i++) {
            if (board[i][0].equals(player) && board[i][1].equals(player) && board[i][2].equals(player)) return true;
            if (board[0][i].equals(player) && board[1][i].equals(player) && board[2][i].equals(player)) return true;
        }
        if (board[0][0].equals(player) && board[1][1].equals(player) && board[2][2].equals(player)) return true;
        if (board[0][2].equals(player) && board[1][1].equals(player) && board[2][0].equals(player)) return true;
        return false;
    }

    private boolean checkMicroWin(String[][] board, int microX, int microY, String player) {
        int sx = microX * 3, sy = microY * 3;
        for (int i = 0; i < 3; i++) {
            if (board[sx+i][sy].equals(player) && board[sx+i][sy+1].equals(player) && board[sx+i][sy+2].equals(player)) return true;
            if (board[sx][sy+i].equals(player) && board[sx+1][sy+i].equals(player) && board[sx+2][sy+i].equals(player)) return true;
        }
        if (board[sx][sy].equals(player)   && board[sx+1][sy+1].equals(player) && board[sx+2][sy+2].equals(player)) return true;
        if (board[sx][sy+2].equals(player) && board[sx+1][sy+1].equals(player) && board[sx+2][sy].equals(player))   return true;
        return false;
    }

    // -------------------------------------------------------------------------
    // State generation
    // -------------------------------------------------------------------------

    public void generateAllStates() {
        for (int i = 0; i < totalSize; i++) {
            int num = i;
            String[][] board = allStates[i];
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

    @Override
    public String getBotName() {
        return BOTNAME;
    }
}


