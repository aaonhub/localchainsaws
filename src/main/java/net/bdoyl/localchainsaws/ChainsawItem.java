package net.bdoyl.localchainsaws;

import java.util.List;
import java.util.function.IntSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A real axe (mines wood at axe speed, strips logs, takes axe enchantments) that also fells whole trees (see
 * {@link TreeFeller}) and fights like a chainsaw (see {@link ChainsawCombat}): swings rev and throw sparks, holding
 * right-click revs the engine up and then saws everything just in front of the player.
 */
public class ChainsawItem extends AxeItem {
    private final IntSupplier maxLogs;

    /**
     * {@code maxLogs}: the most logs one felling takes (from the server config, see {@link ChainsawConfig}). The axe's
     * attack damage and speed come in {@code props} (see LocalChainsaws#props).
     */
    public ChainsawItem(Tier tier, IntSupplier maxLogs, Properties props) {
        super(tier, props);
        this.maxLogs = maxLogs;
    }

    public int maxLogs() {
        return maxLogs.getAsInt();
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        boolean hurt = super.hurtEnemy(stack, target, attacker);
        if (attacker instanceof Player player && player.level() instanceof ServerLevel level) ChainsawCombat.impact(level, player, target, false);
        return hurt;
    }

    /**
     * Right-click anywhere (air, a mob, or a block that has no right-click use of its own: logs still get stripped)
     * starts the engine; it saws once revved up, for as long as right-click is held.
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!ChainsawCombat.canStartRev(player, stack, hand)) return InteractionResultHolder.pass(stack);
        player.startUsingItem(hand);
        ChainsawCombat.revUp(level, player);
        return InteractionResultHolder.consume(stack);
    }

    /**
     * Right-click on a log of a natural tree revs into it (see {@link #use}) instead of stripping it: otherwise the first
     * click would strip the trunk and the tree could no longer be felled. Logs of builds strip as with any axe.
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && ChainsawCombat.canStartRev(player, context.getItemInHand(), context.getHand())
                && TreeFeller.isNaturalTree(context.getLevel(), context.getClickedPos(), context.getLevel().getBlockState(context.getClickedPos()))) {
            return InteractionResult.PASS;
        }
        return super.useOn(context);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_DURATION;
    }

    /** As long as right-click is held. */
    static final int USE_DURATION = 72000;

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remainingUseTicks) {
        if (user instanceof ServerPlayer player) ChainsawCombat.revTick(player, stack, USE_DURATION - remainingUseTicks);
    }

    @Override
    public void onStopUsing(ItemStack stack, LivingEntity entity, int count) {
        if (entity instanceof ServerPlayer player) ChainsawCombat.revDown(player, USE_DURATION - count);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.localchainsaws.chainsaw.tooltip", maxLogs()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.localchainsaws.chainsaw.tooltip.rev").withStyle(ChatFormatting.GRAY));
    }
}
