package fr.influencraft.influpet.pets;

import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.eco.core.sound.PlayableSound;
import com.willfp.libreforge.DispatcherKt;
import com.willfp.libreforge.effects.Chain;
import com.willfp.libreforge.effects.executors.impl.NormalExecutorFactory;
import com.willfp.libreforge.triggers.TriggerData;
import com.willfp.libreforge.triggers.impl.TriggerBlank;
import fr.influencraft.influpet.Texts;
import fr.influencraft.influpet.pets.event.PetLevelUpEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Montée de niveau (effets level-up-effects du familier, son, message, comme PetLevelListener d'EcoPets) et
 * désactivation automatique du familier dont les activate-conditions ne sont plus remplies (toutes les secondes).
 */
public final class PetListener implements Listener {

    public PetListener() {
        Bukkit.getScheduler().runTaskTimer(Pets.plugin(), this::checkConditions, 20L, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelUp(PetLevelUpEvent event) {
        Player player = event.getPlayer();
        Pet pet = event.getPet();
        Chain chain = pet.levelUpEffects();
        if (chain != null) {
            // valeurs par défaut de Chain.trigger (Kotlin) : données du joueur, déclencheur vide, exécuteur normal
            var dispatcher = DispatcherKt.toDispatcher(player);
            chain.trigger(dispatcher, new TriggerData(dispatcher, player, null, null, null, null, null, null, null,
                    null, 0.0, 0.0), TriggerBlank.INSTANCE, NormalExecutorFactory.INSTANCE.create());
        }
        Config settings = Pets.settings();
        var sound = PlayableSound.create(settings.getSubsection("level-up.sound"));
        if (sound != null) {
            sound.playTo(player);
        }
        if (settings.getBool("level-up.message.enabled")) {
            for (String line : PetData.inject(settings.getStrings("level-up.message.message"), player, pet,
                    event.getLevel())) {
                player.sendMessage(Texts.of(line));
            }
        }
    }

    private void checkConditions() {
        if (!Pets.enabled() || !Pets.settings().getBool("deactivate-on-failed-conditions")) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Pet pet : PetData.actives(player)) {
                if (!pet.canActivate(player) && PetData.deactivate(player, pet)) {
                    PetMessages.send(player, "pet-auto-deactivated", "%pet%", pet.name());
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PetData.forget(event.getPlayer().getUniqueId());
        PetView.forget(event.getPlayer().getUniqueId());
    }
}
