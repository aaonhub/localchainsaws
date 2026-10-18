package net.bdoyl.localchainsaws;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side tests: grow a real tree, break its bottom log with a chainsaw held by a (fake) player, count what drops.
 * Run alone with ./gradlew runGameTestServer, or inside a full modpack server with
 * -Dforge.enableGameTest=true -Dforge.gameTestServer=true -Dforge.enabledGameTestNamespaces=localchainsaws.
 * They only exist when gametests are enabled; normal servers never register them.
 */
@GameTestHolder(LocalChainsaws.MODID)
@PrefixGameTestTemplate(false)
public class ChainsawGameTests {
    private static final Logger LOG = LoggerFactory.getLogger("localchainsaws-test");
    private static final String ARENA = "arena"; // 21x40x21 of air: data/localchainsaws/structures/arena.nbt
    private static final BlockPos BASE = new BlockPos(10, 1, 10);

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 21; x++) for (int z = 0; z < 21; z++) h.setBlock(x, 0, z, Blocks.GRASS_BLOCK);
    }

    private static void grow(GameTestHelper h, ResourceKey<ConfiguredFeature<?, ?>> tree, BlockPos rel, long seed) {
        ServerLevel level = h.getLevel();
        ConfiguredFeature<?, ?> feature = level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE).getOrThrow(tree);
        boolean ok = feature.place(level, level.getChunkSource().getGenerator(), RandomSource.create(seed), h.absolutePos(rel));
        if (!ok) h.fail("tree " + tree.location() + " did not generate");
    }

    private static int countBlocks(GameTestHelper h, Predicate<BlockState> what) {
        int n = 0;
        for (int x = 0; x < 21; x++) for (int y = 0; y < 40; y++) for (int z = 0; z < 21; z++)
            if (what.test(h.getBlockState(new BlockPos(x, y, z)))) n++;
        return n;
    }

    private static int countLogs(GameTestHelper h) {
        return countBlocks(h, s -> s.is(BlockTags.LOGS));
    }

    private static int countLeaves(GameTestHelper h) {
        return countBlocks(h, s -> s.is(BlockTags.LEAVES));
    }

    private static int dropped(GameTestHelper h, Item item) {
        AABB box = new AABB(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(21, 40, 21))).inflate(2);
        List<ItemEntity> items = h.getLevel().getEntitiesOfClass(ItemEntity.class, box);
        return items.stream().filter(e -> e.getItem().is(item)).mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static FakePlayer player(GameTestHelper h, String name, ItemStack tool, boolean sneaking) {
        FakePlayer p = FakePlayerFactory.get(h.getLevel(), new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), "[" + name + "]"));
        BlockPos at = h.absolutePos(BASE.offset(2, 0, 0));
        p.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, tool);
        p.setShiftKeyDown(sneaking);
        return p;
    }

    private static void cut(GameTestHelper h, FakePlayer p, BlockPos rel) {
        if (!p.gameMode.destroyBlock(h.absolutePos(rel))) h.fail("the player could not break the log");
    }

    /** Whole tree felled, every log dropped as an item, no leaves left, 1 durability per log, sapling replanted if one dropped. */
    private static void fellsWholeTree(GameTestHelper h, ResourceKey<ConfiguredFeature<?, ?>> tree, Item saw, Item logItem, long seed) {
        floor(h);
        grow(h, tree, BASE, seed);
        int logs = countLogs(h), leaves = countLeaves(h);
        int cap = ((ChainsawItem) saw).maxLogs();
        ItemStack tool = new ItemStack(saw);
        cut(h, player(h, "lcs-" + tree.location().getPath(), tool, false), BASE);
        h.runAfterDelay(5, () -> {
            int expect = Math.min(logs, cap);
            int got = dropped(h, logItem), logsLeft = countLogs(h), leavesLeft = countLeaves(h);
            BlockState stump = h.getBlockState(BASE);
            LOG.info("[localchainsaws-test] {} {}: {} logs + {} leaves grown -> {} logs dropped, {} logs / {} leaves left, damage {}, stump {}",
                    saw, tree.location().getPath(), logs, leaves, got, logsLeft, leavesLeft, tool.getDamageValue(), stump.getBlock());
            h.assertTrue(got == expect, "expected " + expect + " " + logItem + " items, got " + got);
            h.assertTrue(logsLeft == logs - expect, "expected " + (logs - expect) + " logs left, found " + logsLeft);
            if (logs <= cap) h.assertTrue(leavesLeft == 0, leavesLeft + " leaves left behind");
            h.assertTrue(tool.getDamageValue() == expect, "expected " + expect + " damage, got " + tool.getDamageValue());
            boolean replanted = stump.is(BlockTags.SAPLINGS);
            int planted = countBlocks(h, st -> st.is(BlockTags.SAPLINGS));
            LOG.info("[localchainsaws-test] {}: {} sapling(s) planted", tree.location().getPath(), planted);
            h.assertTrue(planted <= 4, planted + " saplings planted");
            int saplingsOnGround = h.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(21, 40, 21))).inflate(2))
                    .stream().filter(e -> e.getItem().is(net.minecraft.tags.ItemTags.SAPLINGS)).mapToInt(e -> e.getItem().getCount()).sum();
            h.assertTrue(replanted || saplingsOnGround == 0, "saplings dropped but none was replanted");
            h.succeed();
        });
    }

    @GameTest(template = ARENA)
    public static void ironFellsOak(GameTestHelper h) {
        fellsWholeTree(h, TreeFeatures.OAK, LocalChainsaws.IRON_CHAINSAW.get(), Items.OAK_LOG, 1);
    }

    @GameTest(template = ARENA)
    public static void ironFellsBirch(GameTestHelper h) {
        fellsWholeTree(h, TreeFeatures.BIRCH, LocalChainsaws.IRON_CHAINSAW.get(), Items.BIRCH_LOG, 2);
    }

    @GameTest(template = ARENA)
    public static void diamondFellsSpruce(GameTestHelper h) {
        fellsWholeTree(h, TreeFeatures.SPRUCE, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.SPRUCE_LOG, 3);
    }

    @GameTest(template = ARENA)
    public static void diamondFellsAcacia(GameTestHelper h) {
        fellsWholeTree(h, TreeFeatures.ACACIA, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.ACACIA_LOG, 4);
    }

    @GameTest(template = ARENA)
    public static void diamondFellsDarkOak(GameTestHelper h) {
        fellsWholeTree(h, TreeFeatures.DARK_OAK, LocalChainsaws.DIAMOND_CHAINSAW.get(), Items.DARK_OAK_LOG, 5);
    }

    @GameTest(template = ARENA)
    public static void netheriteFellsGiantJungle(GameTestHelper h) {
        fellsWholeTree(h, TreeFeatures.MEGA_JUNGLE_TREE, LocalChainsaws.NETHERITE_CHAINSAW.get(), Items.JUNGLE_LOG, 6);
    }

    /** A 2x2 trunk of 96 logs: the iron chainsaw takes 64 from the bottom (1 durability each) and leaves the rest standing. */
    @GameTest(template = ARENA)
    public static void ironStopsAtItsCap(GameTestHelper h) {
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
        h.runAfterDelay(5, () -> {
            int got = dropped(h, Items.JUNGLE_LOG);
            LOG.info("[localchainsaws-test] cap: 96 logs, iron chainsaw dropped {}, {} left, damage {}", got, countLogs(h), tool.getDamageValue());
            h.assertTrue(got == 64, "iron should stop at 64 logs, dropped " + got);
            h.assertTrue(countLogs(h) == 32, "expected 32 logs left, found " + countLogs(h));
            h.assertTrue(tool.getDamageValue() == 64, "expected 64 damage, got " + tool.getDamageValue());
            h.succeed();
        });
    }

    /** Sneaking: the chainsaw does nothing special. With Tree Harvester installed it fells the tree instead (once, not twice). */
    @GameTest(template = ARENA, timeoutTicks = 200)
    public static void sneakingLeavesItToTreeHarvester(GameTestHelper h) {
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 8);
        int logs = countLogs(h);
        cut(h, player(h, "lcs-sneak", new ItemStack(LocalChainsaws.IRON_CHAINSAW.get()), true), BASE);
        boolean th = ModList.get().isLoaded("treeharvester");
        h.runAfterDelay(100, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] sneaking (treeharvester loaded: {}): {} logs grown -> {} dropped, {} left", th, logs, got, countLogs(h));
            h.assertTrue(got <= logs, "more logs dropped than the tree had: something felled it twice");
            if (th) h.assertTrue(got == logs, "Tree Harvester should fell the whole tree when sneaking, got " + got + "/" + logs);
            else h.assertTrue(got == 1, "sneaking should chop just one log, got " + got);
            h.succeed();
        });
    }

    /** Logs with player-placed (persistent) leaves are a build, not a tree: only the broken log drops. */
    @GameTest(template = ARENA)
    public static void ignoresPlayerBuiltLogs(GameTestHelper h) {
        floor(h);
        for (int y = 1; y <= 5; y++) h.setBlock(BASE.atY(y), Blocks.OAK_LOG);
        BlockState placed = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 4; y <= 6; y++) {
            BlockPos p = BASE.offset(dx, 0, dz).atY(y);
            if (!h.getBlockState(p).is(Blocks.OAK_LOG)) h.setBlock(p, placed);
        }
        cut(h, player(h, "lcs-build", new ItemStack(LocalChainsaws.NETHERITE_CHAINSAW.get()), false), BASE);
        h.runAfterDelay(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            h.assertTrue(got == 1, "a player-built log column should lose one log, dropped " + got);
            h.assertTrue(countLogs(h) == 4, "expected 4 logs left, found " + countLogs(h));
            h.succeed();
        });
    }

    /** A nearly broken chainsaw cuts what it can and stops with 1 durability left instead of breaking. */
    @GameTest(template = ARENA)
    public static void stopsBeforeTheToolBreaks(GameTestHelper h) {
        floor(h);
        grow(h, TreeFeatures.OAK, BASE, 9);
        ItemStack tool = new ItemStack(LocalChainsaws.IRON_CHAINSAW.get());
        tool.setDamageValue(tool.getMaxDamage() - 4);
        cut(h, player(h, "lcs-worn", tool, false), BASE);
        h.runAfterDelay(5, () -> {
            int got = dropped(h, Items.OAK_LOG);
            LOG.info("[localchainsaws-test] worn chainsaw: {} logs dropped, {} durability left", got, tool.getMaxDamage() - tool.getDamageValue());
            h.assertTrue(!tool.isEmpty() && tool.getMaxDamage() - tool.getDamageValue() == 1, "chainsaw should end on 1 durability");
            h.assertTrue(got == 3, "expected 3 logs (2 extra + the cut one), got " + got);
            h.succeed();
        });
    }

    /** Felling one tree leaves its neighbour, and the neighbour's leaves, standing. */
    @GameTest(template = ARENA)
    public static void neighbourTreeKeepsItsLeaves(GameTestHelper h) {
        floor(h);
        BlockPos other = BASE.offset(4, 0, 0);
        grow(h, TreeFeatures.OAK, BASE.offset(-1, 0, 0), 10);
        int before = countLogs(h);
        grow(h, TreeFeatures.OAK, other, 11);
        int otherLogs = countLogs(h) - before;
        cut(h, player(h, "lcs-neighbour", new ItemStack(LocalChainsaws.DIAMOND_CHAINSAW.get()), false), BASE.offset(-1, 0, 0));
        h.runAfterDelay(5, () -> {
            int logsLeft = countLogs(h), leavesLeft = countLeaves(h);
            LOG.info("[localchainsaws-test] neighbour: {} logs felled, {} logs / {} leaves left", dropped(h, Items.OAK_LOG), logsLeft, leavesLeft);
            h.assertTrue(logsLeft == otherLogs, "the other tree should keep all " + otherLogs + " logs, found " + logsLeft);
            h.assertTrue(leavesLeft >= 20, "the other tree lost its leaves (" + leavesLeft + " left)");
            h.succeed();
        });
    }
}
