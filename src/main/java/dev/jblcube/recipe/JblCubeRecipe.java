package dev.jblcube.recipe;

import dev.jblcube.registry.ModBlocks;
import dev.jblcube.registry.ModRecipeSerializers;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * The cube recipe: an iron frame with a gold ingot in the middle and the wool that picks the colour
 * in the bottom centre.
 *
 * <pre>
 * iii    iron
 * igi    gold in the middle
 * iwi    the wool in the bottom centre
 * </pre>
 */
public record JblCubeRecipe(DyeColor color) implements CraftingRecipe {

    /** Slot positions of the layout above, so the pattern is spelled out in one place only. */
    private static final int[] IRON = CubeRecipeLayout.IRON;
    private static final int GOLD = CubeRecipeLayout.GOLD;
    private static final int WOOL = CubeRecipeLayout.WOOL;

    /** Not visible in the book (the book is never shown for these), but JEI reads it to sort tabs. */
    @Override
    public CraftingBookCategory category() {
        return CraftingBookCategory.MISC;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (input.size() != 9) {
            return false;
        }
        for (int slot : IRON) {
            if (!input.getItem(slot).is(Items.IRON_INGOT)) {
                return false;
            }
        }
        if (!input.getItem(GOLD).is(Items.GOLD_INGOT)) {
            return false;
        }
        return input.getItem(WOOL).is(woolItem(color));
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return getResultItem(registries);
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return CubeRecipeLayout.fitsGrid(width, height);
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return new ItemStack(ModBlocks.cube(color).get().asItem());
    }

    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> all = NonNullList.withSize(9, Ingredient.EMPTY);
        for (int slot : IRON) {
            all.set(slot, Ingredient.of(Items.IRON_INGOT));
        }
        all.set(GOLD, Ingredient.of(Items.GOLD_INGOT));
        all.set(WOOL, Ingredient.of(woolItem(color)));
        return all;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.JBL_CUBE.get();
    }

    @Override
    public boolean showNotification() {
        return false;
    }

    /** The wool that owns this colour, e.g. {@code light_blue} -> {@code minecraft:light_blue_wool}. */
    private static Item woolItem(DyeColor color) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(color.getSerializedName() + "_wool"));
    }
}