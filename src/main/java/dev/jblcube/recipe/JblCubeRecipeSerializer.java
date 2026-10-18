package dev.jblcube.recipe;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.crafting.RecipeSerializer;

public class JblCubeRecipeSerializer implements RecipeSerializer<JblCubeRecipe> {
    public static final MapCodec<JblCubeRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DyeColor.CODEC.fieldOf("color").forGetter(JblCubeRecipe::color)
    ).apply(instance, JblCubeRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, JblCubeRecipe> STREAM_CODEC =
            StreamCodec.composite(
                    DyeColor.STREAM_CODEC,
                    JblCubeRecipe::color,
                    JblCubeRecipe::new);

    @Override
    public MapCodec<JblCubeRecipe> codec() {
        return MAP_CODEC;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, JblCubeRecipe> streamCodec() {
        return STREAM_CODEC;
    }
}