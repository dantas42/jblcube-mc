package dev.jblcube.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.util.ArrayList;
import java.util.List;

public class BucketRecipeSerializer implements RecipeSerializer<BucketRecipe> {
    public static final MapCodec<BucketRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Ingredient.LIST_CODEC_NONEMPTY.fieldOf("ingredients").forGetter(BucketRecipe::ingredients),
            ItemStack.SINGLE_ITEM_CODEC.fieldOf("result").forGetter(BucketRecipe::result)
    ).apply(instance, BucketRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, BucketRecipe> STREAM_CODEC =
            StreamCodec.composite(
                    ingredientList(),
                    BucketRecipe::ingredients,
                    ItemStack.STREAM_CODEC,
                    BucketRecipe::result,
                    BucketRecipe::new);

    /** Ingredient lists have no fixed length, so the count goes first. */
    private static StreamCodec<RegistryFriendlyByteBuf, List<Ingredient>> ingredientList() {
        return StreamCodec.of(
                (buffer, list) -> {
                    buffer.writeVarInt(list.size());
                    for (Ingredient ingredient : list) {
                        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, ingredient);
                    }
                },
                buffer -> {
                    int size = buffer.readVarInt();
                    List<Ingredient> list = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(Ingredient.CONTENTS_STREAM_CODEC.decode(buffer));
                    }
                    return list;
                });
    }

    @Override
    public MapCodec<BucketRecipe> codec() {
        return MAP_CODEC;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, BucketRecipe> streamCodec() {
        return STREAM_CODEC;
    }
}