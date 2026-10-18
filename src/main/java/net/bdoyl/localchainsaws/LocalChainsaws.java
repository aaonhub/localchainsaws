package net.bdoyl.localchainsaws;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tiers;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(LocalChainsaws.MODID)
public class LocalChainsaws {
    public static final String MODID = "localchainsaws";

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);

    // Same damage/speed as the vanilla axe of each tier; maxLogs caps one felling.
    public static final RegistryObject<ChainsawItem> IRON_CHAINSAW = ITEMS.register("iron_chainsaw",
            () -> new ChainsawItem(Tiers.IRON, 6.0F, -3.1F, 64, new Item.Properties()));
    public static final RegistryObject<ChainsawItem> DIAMOND_CHAINSAW = ITEMS.register("diamond_chainsaw",
            () -> new ChainsawItem(Tiers.DIAMOND, 5.0F, -3.0F, 128, new Item.Properties()));
    public static final RegistryObject<ChainsawItem> NETHERITE_CHAINSAW = ITEMS.register("netherite_chainsaw",
            () -> new ChainsawItem(Tiers.NETHERITE, 5.0F, -3.0F, 256, new Item.Properties().fireResistant()));

    public static final RegistryObject<SoundEvent> CHAINSAW_CUT = SOUNDS.register("item.chainsaw.cut",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(MODID, "item.chainsaw.cut")));

    public LocalChainsaws() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        modBus.addListener(this::addToTabs);
        MinecraftForge.EVENT_BUS.register(TreeFeller.class);
        // Self-test for throwaway copies of a modpack server: see SelfTest. Never set on the live server.
        if (Boolean.getBoolean("localchainsaws.selftest")) MinecraftForge.EVENT_BUS.register(SelfTest.class);
    }

    private void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            // Right after the netherite hoe, i.e. at the end of the vanilla tool rows.
            ItemStack after = new ItemStack(Items.NETHERITE_HOE);
            for (RegistryObject<ChainsawItem> saw : java.util.List.of(IRON_CHAINSAW, DIAMOND_CHAINSAW, NETHERITE_CHAINSAW)) {
                ItemStack stack = new ItemStack(saw.get());
                event.getEntries().putAfter(after, stack, net.minecraft.world.item.CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                after = stack;
            }
        }
    }
}
