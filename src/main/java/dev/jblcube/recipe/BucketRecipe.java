package dev.jblcube.recipe;

import dev.jblcube.registry.ModRecipeSerializers;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * A crafting recipe that needs a bucket but never eats it: the bucket is a required part of the
 * pattern and comes straight back in {@link #getRemainingItems(CraftingInput)}, which is what the
 * vanilla grid uses to refill a slot after the result is taken.
 *
 * <p>It is shapeless on the ingredient side, so the number of wheat (or whatever else is listed) is
 * what tells two recipes apart instead of the layout.
 */
public record BucketRecipe(List<Ingredient> ingredients, ItemStack result) implements CraftingRecipe {

    @Override
    public CraftingBookCategory category() {
        return CraftingBookCategory.MISC;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        List<ItemStack> stacks = new ArrayList<>();
        boolean bucket = false;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.BUCKET)) {
                if (bucket) {
                    return false;
                }
                bucket = true;
                continue;
            }
            stacks.add(stack);
        }
        if (!bucket || stacks.size() != ingredients.size()) {
            return false;
        }

        boolean[] used = new boolean[stacks.size()];
        for (Ingredient ingredient : ingredients) {
            boolean found = false;
            for (int i = 0; i < stacks.size(); i++) {
                if (!used[i] && ingredient.test(stacks.get(i))) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return result.copy();
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= ingredients.size() + 1;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result;
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (!stack.isEmpty() && stack.is(Items.BUCKET)) {
                remaining.set(slot, new ItemStack(Items.BUCKET));
            }
        }
        return remaining;
    }

    /** The bucket is listed here so the recipe book shows it, even though it is never consumed. */
    @Override
    public NonNullList<Ingredient> getIngredients() {
        NonNullList<Ingredient> all = NonNullList.create();
        all.add(Ingredient.of(Items.BUCKET));
        all.addAll(ingredients);
        return all;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.BUCKET.get();
    }

    @Override
    public RecipeType<?> getType() {
        return RecipeType.CRAFTING;
    }

    /**
     * The beers are craftable but hidden from the recipe book, which only understands the vanilla
     * crafting categories and warns about every recipe it cannot place.
     */
    @Override
    public boolean showNotification() {
        return false;
    }
}