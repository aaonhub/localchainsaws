package net.bdoyl.localchainsaws;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The tuning numbers, so they can be changed without a new release.
 *
 * Server side: {@code <world>/serverconfig/localchainsaws-server.toml} (Forge creates it with the defaults on the first
 * start; a copy in {@code defaultconfigs/} seeds new worlds). Edits apply without a restart: Forge re-reads the file on
 * save, or, with ModernFix (which turns Forge's file watcher off, as on The Local), after {@code /mfsrc}. Players get the values when they join (only the wind-up length matters on their side, for the
 * saw rising and the rev loop fading in), so after changing the wind-up they see the new timing from their next join.
 *
 * Client side: {@code config/localchainsaws-client.toml} on each player's own computer: how loud the chainsaw is.
 *
 * Every getter falls back to the default while its config isn't loaded (main menu, tooltips in a launcher, early
 * startup), so nothing here can throw.
 */
public final class ChainsawConfig {
    private static final Logger LOG = LoggerFactory.getLogger("localchainsaws");
    // Defaults: the 1.2.3 numbers (Guy 2026-10-19: rev reach 3 blocks, 45 degree cone, 3 targets).
    static final double REACH = 3.0;
    static final double CONE_DEGREES = 45.0;
    static final int MAX_TARGETS = 3;
    static final int WINDUP = 12;
    static final int REV_INTERVAL = 5;
    static final double REV_DPS_FACTOR = 1.1;
    static final int CUT_TICKS = 15;
    static final int IRON_MAX_LOGS = 64, DIAMOND_MAX_LOGS = 128, NETHERITE_MAX_LOGS = 256;
    static final int LOW_DURABILITY_PERCENT = 10;

    static final ForgeConfigSpec SERVER_SPEC;
    static final ForgeConfigSpec CLIENT_SPEC;
    private static final ForgeConfigSpec.DoubleValue reach, coneDegrees, revDpsFactor;
    private static final ForgeConfigSpec.IntValue maxTargets, windupTicks, revIntervalTicks, cutTicks;
    private static final ForgeConfigSpec.IntValue ironMaxLogs, diamondMaxLogs, netheriteMaxLogs, lowDurabilityPercent;
    private static final ForgeConfigSpec.DoubleValue revLoopVolume, soundVolume;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("Holding right-click: the rev attack and revving into trees.").push("rev");
        reach = b.comment("How far the chain reaches, in blocks from the eyes to the nearest point of a hitbox or log (a normal hit reaches 3).")
                .defineInRange("reach", REACH, 1.0, 6.0);
        coneDegrees = b.comment("Width of the area sawn, in degrees in total (half to each side of where the player looks).")
                .defineInRange("coneDegrees", CONE_DEGREES, 5.0, 180.0);
        maxTargets = b.comment("Most living things sawn per sweep (the one under the crosshair first, then the nearest).")
                .defineInRange("maxTargets", MAX_TARGETS, 1, 16);
        windupTicks = b.comment("Ticks (20 a second) between pressing right-click and the first bite.")
                .defineInRange("windupTicks", WINDUP, 0, 100);
        revIntervalTicks = b.comment("Ticks between two sweeps once revved up (5 = 4 bites a second).")
                .defineInRange("revIntervalTicks", REV_INTERVAL, 1, 40);
        revDpsFactor = b.comment("Rev damage per second for each thing sawn, compared to perfectly timed axe swings (1.0 = the same).")
                .defineInRange("revDpsFactor", REV_DPS_FACTOR, 0.0, 10.0);
        cutTicks = b.comment("Ticks of revved, continuous aim at a log of a natural tree before it is cut through and the tree comes down.")
                .defineInRange("cutTicks", CUT_TICKS, 1, 400);
        b.pop();
        b.comment("Felling: most logs one cut takes down (bottom up; the rest of a bigger tree stays standing).").push("felling");
        ironMaxLogs = b.defineInRange("ironMaxLogs", IRON_MAX_LOGS, 1, 1024);
        diamondMaxLogs = b.defineInRange("diamondMaxLogs", DIAMOND_MAX_LOGS, 1, 1024);
        netheriteMaxLogs = b.defineInRange("netheriteMaxLogs", NETHERITE_MAX_LOGS, 1, 1024);
        b.pop();
        b.push("wear");
        lowDurabilityPercent = b.comment("Warn the player (action bar + a sputter only they hear) when a chainsaw drops to this share of its",
                        "durability; 0 turns the warning off. The 'worn out' warning at 1 durability left always shows.")
                .defineInRange("lowDurabilityPercent", LOW_DURABILITY_PERCENT, 0, 50);
        b.pop();
        SERVER_SPEC = b.build();

        ForgeConfigSpec.Builder c = new ForgeConfigSpec.Builder();
        c.comment("How loud the chainsaws are on this computer (on top of Minecraft's own Players volume slider).").push("sound");
        revLoopVolume = c.comment("The engine loop while someone holds right-click (0 = silent, 1 = full).")
                .defineInRange("revLoopVolume", 1.0, 0.0, 1.0);
        soundVolume = c.comment("Every other chainsaw sound: rev-up, rev-down, hits and trees coming down.")
                .defineInRange("soundVolume", 1.0, 0.0, 1.0);
        c.pop();
        CLIENT_SPEC = c.build();
    }

    private ChainsawConfig() {}

    static void register(IEventBus modBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
        modBus.addListener((ModConfigEvent.Loading e) -> logValues(e.getConfig(), "loaded"));
        modBus.addListener((ModConfigEvent.Reloading e) -> logValues(e.getConfig(), "changed"));
    }

    /** One line in the server log whenever the server config is read, so an edit can be checked: "... changed: reach 3.0 ...". */
    private static void logValues(ModConfig config, String what) {
        if (config.getSpec() != SERVER_SPEC) return;
        LOG.info("[localchainsaws] server config {}: reach {}, coneDegrees {}, maxTargets {}, windupTicks {}, revIntervalTicks {}, "
                        + "revDpsFactor {}, cutTicks {}, maxLogs {}/{}/{}, lowDurabilityPercent {}", what, reach(), coneDegrees(),
                maxTargets(), windupTicks(), revIntervalTicks(), revDpsFactor(), cutTicks(), ironMaxLogs(), diamondMaxLogs(),
                netheriteMaxLogs(), lowDurabilityPercent());
    }

    private static int get(ForgeConfigSpec spec, ForgeConfigSpec.IntValue v, int dflt) {
        return spec.isLoaded() ? v.get() : dflt;
    }

    private static double get(ForgeConfigSpec spec, ForgeConfigSpec.DoubleValue v, double dflt) {
        return spec.isLoaded() ? v.get() : dflt;
    }

    public static double reach() { return get(SERVER_SPEC, reach, REACH); }
    public static double coneDegrees() { return get(SERVER_SPEC, coneDegrees, CONE_DEGREES); }
    public static int maxTargets() { return get(SERVER_SPEC, maxTargets, MAX_TARGETS); }
    public static int windupTicks() { return get(SERVER_SPEC, windupTicks, WINDUP); }
    public static int revIntervalTicks() { return get(SERVER_SPEC, revIntervalTicks, REV_INTERVAL); }
    public static double revDpsFactor() { return get(SERVER_SPEC, revDpsFactor, REV_DPS_FACTOR); }
    public static int cutTicks() { return get(SERVER_SPEC, cutTicks, CUT_TICKS); }
    public static int ironMaxLogs() { return get(SERVER_SPEC, ironMaxLogs, IRON_MAX_LOGS); }
    public static int diamondMaxLogs() { return get(SERVER_SPEC, diamondMaxLogs, DIAMOND_MAX_LOGS); }
    public static int netheriteMaxLogs() { return get(SERVER_SPEC, netheriteMaxLogs, NETHERITE_MAX_LOGS); }
    public static int lowDurabilityPercent() { return get(SERVER_SPEC, lowDurabilityPercent, LOW_DURABILITY_PERCENT); }
    public static double revLoopVolume() { return get(CLIENT_SPEC, revLoopVolume, 1.0); }
    public static double soundVolume() { return get(CLIENT_SPEC, soundVolume, 1.0); }

    /** For the test scenarios: change a server value for the rest of the run (the spec must be loaded). */
    static void setMaxTargets(int n) { maxTargets.set(n); }
}
