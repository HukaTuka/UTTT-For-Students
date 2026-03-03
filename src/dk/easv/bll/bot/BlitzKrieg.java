package dk.easv.bll.bot;

import dk.easv.bll.game.IGameState;
import dk.easv.bll.move.IMove;

public class BlitzKrieg implements IBot {
    static final String BOTNAME="BlitzKrieg";
    static private String[][][] allStates;
    private final int totalSize = (int) Math.pow(3, 9);

   /* public static void main(String[] args) {
        BlitzKrieg blitz = new BlitzKrieg();
        for (int i = 0; i < 100; i++) {
            printBoard(allStates[i]);
        }
    }

    private static void printBoard(String[][] board){
        for (String[] tt: board) {
            System.out.println(tt[0] + tt[1] + tt[2]);
        }
        System.out.println("-----");
    }*/

    public BlitzKrieg() {
        allStates = new String[totalSize][3][3];
        generateAllStates();
    }

    @Override
    public IMove doMove(IGameState state) {
        return null;

    }

    public void minValue(){

    }

    public void maxValue(){

    }

    //Generates all board states
    private void generateAllStates() {
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


