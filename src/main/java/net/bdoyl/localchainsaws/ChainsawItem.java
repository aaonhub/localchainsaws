package net.bdoyl.localchainsaws;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** A real axe (mines wood at axe speed, strips logs, takes axe enchantments) that also fells whole trees: see {@link TreeFeller}. */
public class ChainsawItem extends AxeItem {
    private final int maxLogs;

    public ChainsawItem(Tier tier, float attackDamage, float attackSpeed, int maxLogs, Properties props) {
        super(tier, attackDamage, attackSpeed, props);
        this.maxLogs = maxLogs;
    }

    public int maxLogs() {
        return maxLogs;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.localchainsaws.chainsaw.tooltip", maxLogs).withStyle(ChatFormatting.GRAY));
    }
}
