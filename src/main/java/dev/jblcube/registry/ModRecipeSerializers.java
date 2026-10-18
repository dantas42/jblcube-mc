package dev.jblcube.registry;

import dev.jblcube.JblCube;
import dev.jblcube.recipe.BucketRecipeSerializer;
import dev.jblcube.recipe.JblCubeRecipeSerializer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRecipeSerializers {
    private static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, JblCube.MOD_ID);

    public static final DeferredHolder<RecipeSerializer<?>, BucketRecipeSerializer> BUCKET =
            RECIPE_SERIALIZERS.register("bucket", BucketRecipeSerializer::new);

    public static final DeferredHolder<RecipeSerializer<?>, JblCubeRecipeSerializer> JBL_CUBE =
            RECIPE_SERIALIZERS.register("cube", JblCubeRecipeSerializer::new);

    private ModRecipeSerializers() {
    }

    public static void register(IEventBus modBus) {
        RECIPE_SERIALIZERS.register(modBus);
    }
}