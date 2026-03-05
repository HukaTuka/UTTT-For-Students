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


