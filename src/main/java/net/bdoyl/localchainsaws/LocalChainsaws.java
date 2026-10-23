package net.bdoyl.localchainsaws;

import java.util.List;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(LocalChainsaws.MODID)
public class LocalChainsaws {
    public static final String MODID = "localchainsaws";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(Registries.PARTICLE_TYPE, MODID);

    // Same damage/speed as the vanilla axe of each tier; maxLogs caps one felling (server config, 64/128/256 by default).
    public static final DeferredItem<ChainsawItem> IRON_CHAINSAW = ITEMS.register("iron_chainsaw",
            () -> new ChainsawItem(Tiers.IRON, ChainsawConfig::ironMaxLogs, props(Tiers.IRON, 6.0F, -3.1F)));
    public static final DeferredItem<ChainsawItem> DIAMOND_CHAINSAW = ITEMS.register("diamond_chainsaw",
            () -> new ChainsawItem(Tiers.DIAMOND, ChainsawConfig::diamondMaxLogs, props(Tiers.DIAMOND, 5.0F, -3.0F)));
    public static final DeferredItem<ChainsawItem> NETHERITE_CHAINSAW = ITEMS.register("netherite_chainsaw",
            () -> new ChainsawItem(Tiers.NETHERITE, ChainsawConfig::netheriteMaxLogs, props(Tiers.NETHERITE, 5.0F, -3.0F).fireResistant()));

    public static final DeferredHolder<SoundEvent, SoundEvent> CHAINSAW_CUT = sound("item.chainsaw.cut");
    /** A swing that lands: a short rev burst. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CHAINSAW_HIT = sound("item.chainsaw.hit");
    /** Right-click pressed: the engine spins up (the wind-up before it saws). */
    public static final DeferredHolder<SoundEvent, SoundEvent> CHAINSAW_REV_UP = sound("item.chainsaw.rev_up");
    /** Right-click let go after the wind-up: the engine winds down. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CHAINSAW_REV_DOWN = sound("item.chainsaw.rev_down");
    /** Seamless loop played by clients while a player holds right-click with a revved-up chainsaw. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CHAINSAW_REV = sound("item.chainsaw.rev");

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPARK = PARTICLES.register("spark", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> BLOOD = PARTICLES.register("blood", () -> new SimpleParticleType(false));

    public LocalChainsaws(IEventBus modBus, ModContainer container) {
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        PARTICLES.register(modBus);
        ChainsawConfig.register(modBus, container);
        modBus.addListener(this::addToTabs);
        NeoForge.EVENT_BUS.register(TreeFeller.class);
        NeoForge.EVENT_BUS.register(ChainsawCombat.class);
        NeoForge.EVENT_BUS.register(ChainsawWear.class);
        if (FMLEnvironment.dist == Dist.CLIENT) ChainsawClient.init(modBus);
        // Self-test for throwaway copies of a modpack server: see SelfTest. Never set on the live server.
        if (Boolean.getBoolean("localchainsaws.selftest")) NeoForge.EVENT_BUS.register(SelfTest.class);
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    /** The vanilla axe's attack damage and speed for the tier (1.21: item attribute modifiers are a data component). */
    private static Item.Properties props(Tier tier, float attackDamage, float attackSpeed) {
        return new Item.Properties().attributes(AxeItem.createAttributes(tier, attackDamage, attackSpeed));
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id(name)));
    }

    private void addToTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            // Right after the netherite hoe, i.e. at the end of the vanilla tool rows.
            ItemStack after = new ItemStack(Items.NETHERITE_HOE);
            for (DeferredItem<ChainsawItem> saw : List.of(IRON_CHAINSAW, DIAMOND_CHAINSAW, NETHERITE_CHAINSAW)) {
                ItemStack stack = new ItemStack(saw.get());
                event.insertAfter(after, stack, CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
                after = stack;
            }
        }
    }
}
