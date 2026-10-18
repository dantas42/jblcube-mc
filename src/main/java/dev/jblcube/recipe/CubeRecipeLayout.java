package dev.jblcube.recipe;

/**
 * The shape of the cube crafting recipe, kept apart from the recipe so it can be reasoned about and
 * tested on its own.
 *
 * <pre>
 * 0 1 2    iron
 * 3 4 5    iron  gold  iron
 * 6 7 8    iron  wool  iron
 * </pre>
 *
 * <p>A crafting grid has nine slots: seven iron ingots form the frame, the gold ingot sits in the
 * middle and the wool that picks the colour goes in the bottom centre.
 */
final class CubeRecipeLayout {

    /** The iron frame: every slot except the gold and the wool. */
    static final int[] IRON = {0, 1, 2, 3, 5, 6, 8};

    /** The gold ingot in the very middle. */
    static final int GOLD = 4;

    /** The wool of the chosen colour. */
    static final int WOOL = 7;

    static final int WIDTH = 3;
    static final int HEIGHT = 3;

    private CubeRecipeLayout() {
    }

    /** The pattern fills the grid, so the two by two inventory grid can never work. */
    static boolean fitsGrid(int width, int height) {
        return width >= WIDTH && height >= HEIGHT;
    }
}