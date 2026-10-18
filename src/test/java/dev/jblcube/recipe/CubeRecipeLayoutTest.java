package dev.jblcube.recipe;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the crafting layout, which is invisible in game: the iron frame, the gold in the middle and
 * the wool that picks the colour can only be told apart by reading the code.
 */
class CubeRecipeLayoutTest {

    /** Wraps the array, so the assertion compares the numbers and not two different arrays. */
    private static Set<Integer> slots(int... values) {
        return new HashSet<>(Arrays.stream(values).boxed().toList());
    }

    @Test
    void ironFillsEverySlotExceptTheGoldAndTheWool() {
        assertEquals(Set.of(0, 1, 2, 3, 5, 6, 8), slots(CubeRecipeLayout.IRON));
    }

    @Test
    void goldIsInTheMiddle() {
        assertEquals(4, CubeRecipeLayout.GOLD);
    }

    @Test
    void woolIsInTheBottomCentre() {
        assertEquals(7, CubeRecipeLayout.WOOL);
    }

    @Test
    void everySlotOfTheGridIsClaimedExactlyOnce() {
        Set<Integer> claimed = slots(CubeRecipeLayout.IRON);
        assertTrue(claimed.add(CubeRecipeLayout.GOLD), "gold slot claimed twice");
        assertTrue(claimed.add(CubeRecipeLayout.WOOL), "wool slot claimed twice");
        assertEquals(9, claimed.size());
    }

    @Test
    void recipeOnlyFitsInAFullGrid() {
        assertTrue(CubeRecipeLayout.fitsGrid(3, 3));
        assertFalse(CubeRecipeLayout.fitsGrid(2, 2));
        assertFalse(CubeRecipeLayout.fitsGrid(2, 3));
        assertFalse(CubeRecipeLayout.fitsGrid(3, 2));
    }
}