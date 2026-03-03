package dk.easv.bll.bot;

import dk.easv.bll.field.IField;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;

import java.util.List;

public class PrototypeKrieg implements IBot {
    static final String BOTNAME="PrototypeKrieg";
    static private String[][][] allStates;
    private final int totalSize = (int) Math.pow(3, 9);


    public PrototypeKrieg() {
        allStates = new String[totalSize][3][3];
        generateAllStates();
    }


    @Override
    public IMove doMove(IGameState state) {
        List<IMove> moves = state.getField().getAvailableMoves();
        if (moves.isEmpty()) return null;

        IMove bestMove = moves.get(0);
        int bestValue = Integer.MIN_VALUE;

        int depth = Math.min(4, moves.size()); // dynamic depth

        for (IMove move : moves) {
            makeMove(state, move, true); // apply move
            int value = minimaxFast(state, depth - 1, false, Integer.MIN_VALUE, Integer.MAX_VALUE);
            undoMove(state, move); // undo move

            if (value > bestValue || (value == bestValue && Math.random() < 0.5)) {
                bestValue = value;
                bestMove = move;
            }
        }

        return bestMove;
    }


    private int minimaxFast(IGameState state, int depth, boolean isMax, int alpha, int beta) {
        if (depth == 0 || isTerminal(state)) return evaluateFast(state);

        List<IMove> moves = state.getField().getAvailableMoves();
        if (isMax) {
            int maxEval = Integer.MIN_VALUE;
            for (IMove move : moves) {
                makeMove(state, move, true);
                int eval = minimaxFast(state, depth - 1, false, alpha, beta);
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
                int eval = minimaxFast(state, depth - 1, true, alpha, beta);
                undoMove(state, move);
                minEval = Math.min(minEval, eval);
                beta = Math.min(beta, eval);
                if (beta <= alpha) break;
            }
            return minEval;
        }
    }


    private int evaluateFast(IGameState state) {
        String[][] macro = state.getField().getMacroboard();
        int score = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (macro[i][j].equals("0")) score += 10;
                else if (macro[i][j].equals("1")) score -= 10;
            }
        }

        // check terminal quickly
        if (checkWinMacro(macro, "0")) return 1000;
        if (checkWinMacro(macro, "1")) return -1000;
        return score;
    }


    private void undoMove(IGameState state, IMove move) {
        String[][] board = state.getField().getBoard();
        String[][] macro = state.getField().getMacroboard();

        int x = move.getX();
        int y = move.getY();
        board[x][y] = IField.EMPTY_FIELD;

        // recompute affected microboard
        int microX = x / 3;
        int microY = y / 3;

        if (!checkMicroWin(board, microX, microY, "0") && !checkMicroWin(board, microX, microY, "1")) {
            macro[microX][microY] = IField.EMPTY_FIELD;
        }

        // recalc available boards
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (macro[i][j].equals(IField.AVAILABLE_FIELD)) macro[i][j] = IField.EMPTY_FIELD;
            }
        }

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


    private boolean isTerminal(IGameState state) {

        String[][] macro = state.getField().getMacroboard();

        return checkWin(macro, "0")
                || checkWin(macro, "1")
                || state.getField().isFull();
    }


    private boolean checkWin(String[][] board, String player) {

        for (int i = 0; i < 3; i++) {
            if (board[i][0].equals(player) && board[i][1].equals(player) && board[i][2].equals(player))
                return true;
            if (board[0][i].equals(player) && board[1][i].equals(player) && board[2][i].equals(player))
                return true;
        }
        if (board[0][0].equals(player) && board[1][1].equals(player) && board[2][2].equals(player))
            return true;
        if (board[0][2].equals(player) && board[1][1].equals(player) && board[2][0].equals(player))
            return true;
        return false;
    }


    private boolean checkWinMacro(String[][] macro, String player) {
        // Check rows and columns
        for (int i = 0; i < 3; i++) {
            if (macro[i][0].equals(player) && macro[i][1].equals(player) && macro[i][2].equals(player))
                return true;
            if (macro[0][i].equals(player) && macro[1][i].equals(player) && macro[2][i].equals(player))
                return true;
        }
        // Check diagonals
        if (macro[0][0].equals(player) && macro[1][1].equals(player) && macro[2][2].equals(player))
            return true;
        if (macro[0][2].equals(player) && macro[1][1].equals(player) && macro[2][0].equals(player))
            return true;

        return false;
    }


    private String[][] copyBoard(String[][] original) {
        String[][] copy = new String[original.length][original[0].length];

        for (int i = 0; i < original.length; i++) {
            System.arraycopy(original[i], 0, copy[i], 0, original[i].length);
        }

        return copy;
    }


    private void makeMove(IGameState state, IMove move, boolean isBot) {
        String player = isBot ? "0" : "1";
        int x = move.getX();
        int y = move.getY();

        String[][] board = state.getField().getBoard();
        String[][] macro = state.getField().getMacroboard();

        // Place move
        board[x][y] = player;

        // Update microboard win
        int microX = x / 3;
        int microY = y / 3;
        if (checkMicroWin(board, microX, microY, player)) {
            macro[microX][microY] = player;
        }

        // Update next active microboard
        int nextMicroX = x % 3;
        int nextMicroY = y % 3;

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                if (macro[i][j].equals(IField.AVAILABLE_FIELD)) {
                    macro[i][j] = IField.EMPTY_FIELD;
                }
            }
        }

        if (macro[nextMicroX][nextMicroY].equals(IField.EMPTY_FIELD)) {
            macro[nextMicroX][nextMicroY] = IField.AVAILABLE_FIELD;
        } else {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    if (macro[i][j].equals(IField.EMPTY_FIELD)) {
                        macro[i][j] = IField.AVAILABLE_FIELD;
                    }
                }
            }
        }

        state.setMoveNumber(state.getMoveNumber() + 1);
    }


    private boolean checkMicroWin(String[][] board, int microX, int microY, String player) {

        int startX = microX * 3;
        int startY = microY * 3;

        for (int i = 0; i < 3; i++) {
            if (board[startX + i][startY].equals(player) &&
                    board[startX + i][startY + 1].equals(player) &&
                    board[startX + i][startY + 2].equals(player))
                return true;

            if (board[startX][startY + i].equals(player) &&
                    board[startX + 1][startY + i].equals(player) &&
                    board[startX + 2][startY + i].equals(player))
                return true;
        }

        if (board[startX][startY].equals(player) &&
                board[startX + 1][startY + 1].equals(player) &&
                board[startX + 2][startY + 2].equals(player))
            return true;

        if (board[startX][startY + 2].equals(player) &&
                board[startX + 1][startY + 1].equals(player) &&
                board[startX + 2][startY].equals(player))
            return true;

        return false;
    }


    //Generates all board states
    public void generateAllStates() {
        //gives different values like x, o or -
        String[] values = {" - ", " X ", " O "};

        //loops over every single state and writes them down
        for (int i = 0; i < totalSize; i++) {
            int num = i;
            String[][] board = allStates[i];

            for (int j = 0; j < 9; j++) {
                int valueIndex = num%3;
                num /= 3;
                int row = j / 3;
                int col = j % 3;
                board[row][col] = values[valueIndex];
            }
            allStates[i] = board;
        }

    }

    @Override
    public String getBotName() {
        return BOTNAME;
    }
}


