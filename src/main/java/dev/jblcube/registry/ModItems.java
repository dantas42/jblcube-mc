package dev.jblcube.registry;

import dev.jblcube.JblCube;
import dev.jblcube.item.BeerItem;
import dev.jblcube.item.JblCubeItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(JblCube.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, JblCube.MOD_ID);

    /** One cube item per wool colour, in dye order. */
    private static final Map<DyeColor, DeferredItem<JblCubeItem>> CUBES = new LinkedHashMap<>();

    /**
     * A beer: the item id, the label shown in game, the colour of the can and how many wheat the
     * recipe asks for. Wheat is the only thing that gets eaten, and the amount is what keeps the
     * recipes from matching each other.
     */
    public record Beer(String id, String name, int tint, int wheat) {
    }

    private static final List<Beer> BEER_LIST = List.of(
            new Beer("skol", "Cerveja Skol", 0xF5C518, 1),
            new Beer("brahma", "Cerveja Brahma", 0xD8232A, 2),
            new Beer("antarctica", "Cerveja Antarctica", 0x2A6FC9, 3),
            new Beer("original", "Cerveja Original", 0x1E7A3C, 4),
            new Beer("bohemia", "Cerveja Bohemia", 0xE8853A, 5),
            new Beer("kaiser", "Cerveja Kaiser", 0xBFC3C8, 6),
            new Beer("serramalte", "Cerveja Serramalte", 0x6B4327, 7));

    private static final Map<String, DeferredItem<BeerItem>> BEERS = new LinkedHashMap<>();

    static {
        for (DyeColor color : DyeColor.values()) {
            DeferredItem<JblCubeItem> item = ITEMS.registerItem("jbl_cube_" + color.getSerializedName(),
                    properties -> new JblCubeItem(ModBlocks.cube(color).get(), properties),
                    new Item.Properties().stacksTo(1));
            CUBES.put(color, item);
        }
        for (Beer beer : BEER_LIST) {
            BEERS.put(beer.id(), ITEMS.registerItem("beer_" + beer.id(), BeerItem::new, new Item.Properties()));
        }
    }

    private ModItems() {
    }

    public static List<DeferredItem<JblCubeItem>> cubes() {
        return List.copyOf(CUBES.values());
    }

    public static DeferredItem<JblCubeItem> cube(DyeColor color) {
        return CUBES.get(color);
    }

    public static DeferredItem<BeerItem> beer(String id) {
        return BEERS.get(id);
    }

    public static DeferredItem<JblCubeItem> firstCube() {
        return CUBES.values().iterator().next();
    }

    public static List<Beer> beers() {
        return BEER_LIST;
    }

    public static @Nullable Beer beerOf(Item item) {
        for (Beer beer : BEER_LIST) {
            if (BEERS.get(beer.id()).get() == item) {
                return beer;
            }
        }
        return null;
    }

    /** Any of the sixteen coloured cubes, so callers do not need to know the item ids. */
    public static boolean isCube(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return CUBES.values().stream().anyMatch(cube -> cube.get() == stack.getItem());
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);

        CREATIVE_TABS.register("music", () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("itemGroup.jblcube"))
                .icon(() -> firstCube().toStack())
                .displayItems((parameters, output) -> {
                    CUBES.values().forEach(cube -> output.accept(cube.toStack()));
                    BEERS.values().forEach(beer -> output.accept(beer.toStack()));
                })
                .build());
        CREATIVE_TABS.register(modBus);
    }
}