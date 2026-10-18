package net.bdoyl.localchainsaws;

import com.mojang.authlib.GameProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grow a real tree, break its bottom log with a chainsaw held by a fake player, count what drops. Each scenario runs in
 * a {@link TestArena}: as GameTests in dev ({@link ChainsawGameTests}, ./gradlew runGameTestServer) or inside a full
 * modpack server with -Dlocalchainsaws.selftest=true ({@link SelfTest}).
 */
final class ChainsawScenarios {
    private static final Logger LOG = LoggerFactory.getLogger("localchainsaws-test");
    private static final BlockPos BASE = new BlockPos(10, 1, 10);

    /** Every scenario by name, with the ticks it may take. */
    record Scenario(Consumer<TestArena> run, int maxTicks) {}

    static final Map<String, Scenario> ALL = new LinkedHashMap<>();
    static {
        ALL.put("iron_fells_oak", new Scenario(ChainsawScenarios::ironFellsOak, 100));
        ALL.put("iron_fells_birch", new Scenario(ChainsawScenarios::ironFellsBirch, 100));
        ALL.put("diamond_fells_spruce", new Scenario(ChainsawScenarios::diamondFellsSpruce, 100));
        ALL.put("diamond_fells_acacia", new Scenario(ChainsawScenarios::diamondFellsAcacia, 100));
        ALL.put("diamond_fells_dark_oak", new Scenario(ChainsawScenarios::diamondFellsDarkOak, 100));
        ALL.put("netherite_fells_giant_jungle", new Scenario(ChainsawScenarios::netheriteFellsGiantJungle, 100));
        ALL.put("iron_stops_at_its_cap", new Scenario(ChainsawScenarios::ironStopsAtItsCap, 100));
        ALL.put("sneaking_leaves_it_to_tree_harvester", new Scenario(ChainsawScenarios::sneakingLeavesItToTreeHarvester, 200));
        ALL.put("ignores_player_built_logs", new Scenario(ChainsawScenarios::ignoresPlayerBuiltLogs, 100));
        ALL.put("stops_before_the_tool_breaks", new Scenario(ChainsawScenarios::stopsBeforeTheToolBreaks, 100));
        ALL.put("neighbour_tree_keeps_its_leaves", new Scenario(ChainsawScenarios::neighbourTreeKeepsItsLeaves, 100));
    }

    private ChainsawScenarios() {}

    private static void floor(TestArena h) {
        for (int x = 0; x < 21; x++) for (int z = 0; z < 21; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK);
    }

    private static void grow(TestArena h, ResourceKey<ConfiguredFeature<?, ?>> tree, BlockPos rel, long seed) {
        ServerLevel level = h.level();
        ConfiguredFeature<?, ?> feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getOrThrow(tree);
        boolean ok = feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), h.abs(rel));
        h.check(ok, "tree " + tree.location() + " did not generate");
    }

    private static int countBlocks(TestArena h, Predicate<BlockState> what) {
        int n = 0;
        for (int x = 0; x < 21; x++) for (int y = 0; y < 40; y++) for (int z = 0; z < 21; z++)
            if (what.test(h.getBlockState(new BlockPos(x, y, z)))) n++;
        return n;
    }

    private static int countLogs(TestArena h) {
        return countBlocks(h, s -> s.is(BlockTags.LOGS));
    }

    private static int countLeaves(TestArena h) {
        return countBlocks(h, s -> s.is(BlockTags.LEAVES));
    }

    private static int dropped(TestArena h, Item item) {
        AABB box = new AABB(h.abs(BlockPos.ZERO), h.abs(new BlockPos(21, 40, 21))).inflate(2);
        List<ItemEntity> items = h.level().getEntitiesOfClass(ItemEntity.class, box);
        return items.stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static FakePlayer player(TestArena h, String name, ItemStack tool, boolean sneaking) {
        FakePlayer p = FakePlayerFactory.get(h.level(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), "[" + name + "]"));
        BlockPos at = h.abs(BASE.offset(2, 0, 0));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool);
        p.setShiftKeyDown(sneaking);
        p.setPose(sneaking ? net.minecraft.world.entity.Pose.CROUCHING : net.minecraft.world.entity.Pose.STANDING); // a fake player never ticks its pose
        return p;
    }

    private static void cut(TestArena h, FakePlayer p, BlockPos rel) {
        h.check(tryCut(h, p, rel), "the player could not break the log");
    }

    /** False when something cancelled the break (Tree Harvester does, as it breaks the logs itself). */
    private static boolean tryCut(TestArena h, FakePlayer p, BlockPos rel) {
        BlockPos at = h.abs(rel);
        // Like a real player: mining starts (BreakSpeed event, which Tree Harvester uses to record a harvest attempt),
        // then the block breaks.
        p.getDigSpeed(h.level().getBlockState(at), at);
        return p.gameMode.destroyBlock(at);
    }

    /** Whole tree felled, every log dropped as an item, no leaves left, 1 durability per log, sapling replanted if one dropped. */
    static void fellsWholeTree(TestArena h, ResourceKey<ConfiguredFeature<?, ?>> tree, Item saw, Item logItem, long seed) {
        floor(h);
        grow(h, tree, BASE, seed);
        int logs = countLogs(h), leaves = countLeaves(h);
        int cap = ((ChainsawItem) saw).maxLogs();
        ItemStack tool = new ItemStack(saw);
        cut(h, player(h, "lcs-" + tree.location().getPath(), tool, false), BASE);
        h.later(5, () -> {
            int expect = Math.min(logs, cap);
            int got = dropped(h, logItem), logsLeft = countLogs(h), leavesLeft = countLeaves(h);
            BlockState stump = h.getBlockState(BASE);
            LOG.info("[localchainsaws-test] {} {}: {} logs + {} leaves grown -> {} logs dropped, {} logs / {} leaves left, damage {}, stump {}",
                    saw, tree.location().getPath(), logs, leaves, got, logsLeft, leavesLeft, tool.getDamageValue(), stump.getBlock());
            h.check(got == expect, "expected " + expect + " " + logItem + " items, got " + got);
            h.check(logsLeft == logs - expect, "expected " + (logs - expect) + " logs left, found " + logsLeft);
            if (logs <= cap) h.check(leavesLeft == 0, leavesLeft + " leaves left behind");
            h.check(tool.getDamageValue() == expect, "expected " + expect + " damage, got " + tool.getDamageValue());
            boolean replanted = stump.is(BlockTags.SAPLINGS);
            int planted = countBlocks(h, st -> st.is(BlockTags.SAPLINGS));
            LOG.info("[localchainsaws-test] {}: {} sapling(s) planted", tree.location().getPath(), planted);
            h.check(planted <= 4, planted + " saplings planted");
            int saplingsOnGround = h.level().getEntitiesOfClass(ItemEntity.class,
                    new AABB(h.abs(BlockPos.ZERO), h.abs(new BlockPos(21, 40, 21))).inflate(2))
                    .stream().filter(e -> e.getItem().is(net.minecraft.tags.ItemTags.SAPLINGS)).mapToInt(e -> e.getItem().getCount()).sum();
            h.check(replanted || saplingsOnGround == 0, "saplings dropped but none was replanted");
            h.pass();
        });
    }

    static void ironFellsOak(TestArena h) {
        fellsWholeTree(h, TreeFeatures.OAK, LocalChainsaws.IRON_CHAINSAW.get(), Items.OAK_LOG, 1);
    }

    static void ironFellsBirch(TestArena h) {
        fellsWholeTree(h, TreeFeatures.BIRCH, LocalChainsaws.IRON_CHAINSAW.get(), Items.BIRCH_LOG, 2);
    }

    static void diamondFellsSpruce(TestArena h) {
        fellsWholeTree(h, TreeFeatures.SPRUCE, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.SPRUCE_LOG, 3);
    }

    static void diamondFellsAcacia(TestArena h) {
        fellsWholeTree(h, TreeFeatures.ACACIA, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.ACACIA_LOG, 4);
    }

    static void diamondFellsDarkOak(TestArena h) {
        fellsWholeTree(h, TreeFeatures.DARK_OAK, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.DARK_OAK_LOG, 5);
    }

    static void netheriteFellsGiantJungle(TestArena h) {
        fellsWholeTree(h, TreeFeatures.MEGA_JUNGLE_TREE, LocalChainsaws.NETHERITE_CHAINSAW.get(), Items.JUNGLE_LOG, 6);
    }

    /** A 2x2 trunk of 96 logs: the iron chainsaw takes 64 from the bottom (1 durability each) and leaves the rest standing. */
    static void ironStopsAtItsCap(TestArena h) {
        floor(h);
        BlockState leaf = Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
        for (int y = 1; y <= 24; y++) for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) h.setBlock(BASE.offset(dx, 0, dz).atY(y), Blocks.JUNGLE_LOG);
        for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) {
            BlockPos p = BASE.offset(dx, 0, dz).atY(25);
            h.setBlock(p, leaf);
            if (dx < 0 || dx > 1 || dz < 0 || dz > 1) h.setBlock(p.below(), leaf);
        }
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        cut(h, player(h, "lcs-cap", tool, false), BASE);
        h.later(5, () -> {
            int got = dropped(h, Items.JUNGLE_LOG);
            LOG.info("[localchainsaws-test] cap: 96 logs, iron chainsaw dropped {}, {} left, damage {}", got, countLogs(h), tool.getDamageValue());
            h.check(got == 64, "iron should stop at 64 logs, dropped " + got);
            h.check(countLogs(h) == 32, "expected 32 logs left, found " + countLogs(h));
            h.check(tool.getDamageValue() == 64, "expected 64 damage, got " + tool.getDamageValue());
            h.pass();
        });
    }

    /** Sneaking: the chainsaw does nothing special. With Tree Harvester installed it fells the tree instead (once, not twice). */
    static void sneakingLeavesItToTreeHarvester(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 8);
        int logs = countLogs(h);
        tryCut(h, player(h, "lcs-sneak", new ItemStack(LocalChainsaws.IRON_CHAINSAW.get()), true), BASE);
        boolean th = ModList.get().isLoaded("treeharvester");
        h.later(100, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] sneaking (treeharvester loaded: {}): {} logs grown -> {} dropped, {} left", th, logs, got, countLogs(h));
            h.check(got <= logs, "more logs dropped than the tree had: something felled it twice");
            if (th) h.check(got == logs, "Tree Harvester should fell the whole tree when sneaking, got " + got + "/" + logs);
            else h.check(got == 1, "sneaking should chop just one log, got " + got);
            h.pass();
        });
    }

    /** Logs with player-placed (persistent) leaves are a build, not a tree: only the broken log drops. */
    static void ignoresPlayerBuiltLogs(TestArena h) {
        floor(h);
        for (int y = 1; y <= 5; y++) h.setBlock(BASE.atY(y), Blocks.OAK_LOG);
        BlockState placed = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 4; y <= 6; y++) {
            BlockPos p = BASE.offset(dx, 0, dz).atY(y);
            if (!h.getBlockState(p).is(Blocks.OAK_LOG)) h.setBlock(p, placed);
        }
        cut(h, player(h, "lcs-build", new ItemStack(LocalChainsaws.NETHERITE_CHAINSAW.get()), false), BASE);
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            h.check(got == 1, "a player-built log column should lose one log, dropped " + got);
            h.check(countLogs(h) == 4, "expected 4 logs left, found " + countLogs(h));
            h.pass();
        });
    }

    /** A nearly broken chainsaw cuts what it can and stops with 1 durability left instead of breaking. */
    static void stopsBeforeTheToolBreaks(TestArena h) {
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 9);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        tool.setDamageValue(tool.getMaxDamage() - 4);
        cut(h, player(h, "lcs-worn", tool, false), BASE);
        h.later(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] worn chainsaw: {} logs dropped, {} durability left", got, tool.getMaxDamage() - tool.getDamageValue());
            h.check(!tool.isEmpty() && tool.getMaxDamage() - tool.getDamageValue() == 1, "chainsaw should end on 1 durability");
            h.check(got == 3, "expected 3 logs (2 extra + the cut one), got " + got);
            h.pass();
        });
    }

    /** Felling one tree leaves its neighbour, and the neighbour's leaves, standing. */
    static void neighbourTreeKeepsItsLeaves(TestArena h) {
        floor(h);
        BlockPos other = BASE.offset(4, 0, 0);
        grow(h, TreeFeatures.OAK, BASE.offset(-1, 0, 0), 10);
        int before = countLogs(h);
        grow(h, TreeFeatures.OAK, other, 11);
        int otherLogs = countLogs(h) - before;
        cut(h, player(h, "lcs-neighbour", new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get()), false), BASE.offset(-1, 0, 0));
        h.later(5, () -> {
            int logsLeft = countLogs(h), leavesLeft = countLeaves(h);
            LOG.info("[localchainsaws-test] neighbour: {} logs felled, {} logs / {} leaves left", dropped(h, Items.OAK_LOG), logsLeft, leavesLeft);
            h.check(logsLeft == otherLogs, "the other tree should keep all " + otherLogs + " logs, found " + logsLeft);
            h.check(leavesLeft >= 20, "the other tree lost its leaves (" + leavesLeft + " left)");
            h.pass();
        });
    }
}
