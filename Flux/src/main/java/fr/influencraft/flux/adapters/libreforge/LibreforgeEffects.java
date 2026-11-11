package fr.influencraft.flux.adapters.libreforge;

import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.config.TransientConfig;
import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.libreforge.DispatcherKt;
import com.willfp.libreforge.Holder;
import com.willfp.libreforge.HolderProviderKt;
import com.willfp.libreforge.ProvidedHolder;
import com.willfp.libreforge.SimpleProvidedHolder;
import com.willfp.libreforge.ViolationContext;
import com.willfp.libreforge.conditions.ConditionList;
import com.willfp.libreforge.conditions.Conditions;
import com.willfp.libreforge.effects.EffectList;
import fr.influencraft.flux.api.effects.Effects;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Effects of the suite run by libreforge: a bundle is a holder, Flux is the one holder provider. */
public final class LibreforgeEffects implements Effects.Engine {

    private record Bundle(NamespacedKey id, EffectList effects, ConditionList conditions) implements Effects.Bundle, Holder {
        @Override
        public EffectList getEffects() {
            return effects;
        }

        @Override
        public ConditionList getConditions() {
            return conditions;
        }

        @Override
        public NamespacedKey getId() {
            return id;
        }
    }

    private final EcoPlugin libreforge = (EcoPlugin) Bukkit.getPluginManager().getPlugin("libreforge");

    @Override
    public Effects.Bundle compile(String owner, String id, List<Map<?, ?>> effects, List<Map<?, ?>> conditions) {
        ViolationContext context = new ViolationContext(libreforge, owner + " " + id);
        return new Bundle(new NamespacedKey(owner, id.toLowerCase(Locale.ROOT)),
                com.willfp.libreforge.effects.Effects.INSTANCE.compile(configs(effects), context),
                Conditions.INSTANCE.compile(configs(conditions), context));
    }

    @SuppressWarnings("unchecked") // maps read from YAML: their keys are strings
    private static List<Config> configs(List<Map<?, ?>> maps) {
        List<Config> configs = new ArrayList<>();
        for (Map<?, ?> map : maps) configs.add(new TransientConfig((Map<String, Object>) map));
        return configs;
    }

    @Override
    @SuppressWarnings("unchecked") // map read from YAML: its keys are strings
    public Effects.Counter count(String owner, String id, Map<?, ?> config, Effects.Accumulator accumulator) {
        ViolationContext context = new ViolationContext(libreforge, owner + " " + id);
        com.willfp.libreforge.counters.Counter counter = com.willfp.libreforge.counters.Counters.compile(
                new TransientConfig((Map<String, Object>) config), context);
        if (counter == null) return null;
        counter.bind(accumulator::accept);
        return counter::unbind;
    }

    @Override
    public void start() {
        HolderProviderKt.registerGenericHolderProvider(dispatcher -> {
            List<ProvidedHolder> holders = new ArrayList<>();
            if (dispatcher.getDispatcher() instanceof Player player) {
                for (Effects.Bundle bundle : Effects.bundles(player)) {
                    if (bundle instanceof Bundle holder) holders.add(new SimpleProvidedHolder(holder));
                }
            }
            return holders;
        });
    }

    @Override
    public void refresh(Player player) {
        HolderProviderKt.refreshHolders(DispatcherKt.toDispatcher(player));
    }
}
