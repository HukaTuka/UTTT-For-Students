package dk.easv.bll.bot;


import dk.easv.bll.field.IField;
import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;
import java.util.List;

public class BlitzKrieg implements IBot {
    static final String BOTNAME="BlitzKrieg";
    static private String[][][] allStates;
    private final int totalSize = (int) Math.pow(3, 9);


    public BlitzKrieg() {
        allStates = new String[totalSize][3][3];
        generateAllStates();
    }


    @Override
    public IMove doMove(IGameState state) {

        return null;
    }

    private int miniMax(IGameState state){





        return 1;
    }

    //isTerminal returns the checkWin method, checking if there is a win for either player.
    private boolean isTerminal(IGameState state) {

        String[][] macro = state.getField().getMacroboard();

        return checkWin(macro, "0")
                || checkWin(macro, "1")
                || state.getField().isFull();
    }


    //Looks at the board and checks if there is a winning line in the macro boards, returns true if there is
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


