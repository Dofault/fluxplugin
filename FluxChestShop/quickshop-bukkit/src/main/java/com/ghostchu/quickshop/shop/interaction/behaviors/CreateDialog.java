package com.ghostchu.quickshop.shop.interaction.behaviors;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.shop.IShopType;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.interaction.InteractionBehavior;
import com.ghostchu.quickshop.api.shop.interaction.InteractionClick;
import com.ghostchu.quickshop.api.shop.interaction.InteractionType;
import com.ghostchu.quickshop.shop.SimpleInfo;
import com.ghostchu.quickshop.shop.SimpleShopManager;
import com.ghostchu.quickshop.util.Util;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.ghostchu.quickshop.shop.SimpleShopManager.BUYING_TYPE;
import static com.ghostchu.quickshop.shop.SimpleShopManager.SELLING_TYPE;

/**
 * FluxChestShop : création rapide d'une échoppe par boîte de dialogue (Paper 26.2). Accroché à
 * SNEAKING_RIGHT_CLICK_CONTAINER dans interaction.yml : sneak + clic droit sur un coffre, objet en
 * main, ouvre un dialogue « Vente / Rachat + prix ». Valider pose le panneau et l'objet flottant,
 * exactement comme la création classique (mêmes vérifications, mêmes frais, même liste noire).
 *
 * <p>Un objet de la liste noire (config.yml → blacklist : pancartes, cadres...), un mode de jeu autre
 * que survie, un bloc qui ne peut pas devenir une échoppe ou l'absence de droit de création laissent
 * le clic au jeu sans rien dire : poser une pancarte ou un cadre sur un coffre en sneakant reste
 * possible (BlockLocker, décoration).</p>
 */
public class CreateDialog implements InteractionBehavior {

  private static final MiniMessage MM = MiniMessage.miniMessage();
  private static final String OR = "<#FFD94D>";
  private static final String VIOLET = "<#B376E5>";
  private static final String VERT = "<#2ECF7B>";
  private static final String ROUGE = "<#FF5C5C>";
  /** Glyphe BitBlock brut (U+E023, rendu par le pack ItemsAdder), un dialogue ne passe pas par PlaceholderAPI. */
  private static final String BITBLOCK = "<white></white>";

  @Override
  public String identifier() {

    return "CREATE_DIALOG";
  }

  @Override
  public void handle(final @NotNull QuickShopAPI api, final @Nullable Shop shop, final @NotNull Player player, final @NotNull PlayerInteractEvent event, final @NotNull InteractionClick clickType, final @Nullable InteractionType interaction) {

    if(shop != null || event.getClickedBlock() == null || event.getHand() == null) {
      return;
    }
    final QuickShop plugin = (QuickShop)api;
    final ItemStack item = event.getItem();
    final Block block = event.getClickedBlock();
    if(!plugin.getConfig().getBoolean("shop.quick-create-dialog", true) || !peutEssayer(plugin, player, block, item)) {
      return; // le clic reste au jeu (pose de pancarte, de cadre, de bloc...)
    }
    final SimpleInfo info = Util.prepareShopCreation(player, block, event.getBlockFace(), item);
    if(info == null) {
      return; // refus déjà expliqué au joueur par prepareShopCreation
    }
    event.setCancelled(true);
    event.setUseInteractedBlock(Event.Result.DENY);
    event.setUseItemInHand(Event.Result.DENY);
    ouvrir(plugin, player, info, null, null);
  }

  @Override
  public void handle(final @NotNull QuickShopAPI plugin, final @Nullable Shop shop, final @NotNull Player player, final @NotNull PlayerInteractEntityEvent event, final @NotNull InteractionClick clickType, final @Nullable InteractionType interaction) {
    // Pas de création depuis une entité.
  }

  @Override
  public void handle(final @NotNull QuickShopAPI plugin, final @Nullable Shop shop, final @NotNull Player player, final @NotNull EntityDamageByEntityEvent event, final @NotNull InteractionClick clickType, final @Nullable InteractionType interaction) {
    // Pas de création depuis une entité.
  }

  /** Conditions muettes : si l'une manque, le sneak + clic droit garde son effet normal. */
  private static boolean peutEssayer(final QuickShop plugin, final Player player, final Block block, final @Nullable ItemStack item) {

    if(item == null || item.getType().isAir() || player.getGameMode() != GameMode.SURVIVAL) {
      return false;
    }
    if(plugin.getConfig().getBoolean("disable-quick-create") || plugin.getConfig().getBoolean("shop.disable-quick-create")) {
      return false;
    }
    if(!Util.canBeShop(block)) {
      return false;
    }
    if(!plugin.perm().hasPermission(player, "quickshop.create.sell") && !plugin.perm().hasPermission(player, "quickshop.create.buy")) {
      return false;
    }
    return !plugin.getShopItemBlackList().isBlacklisted(item)
           || plugin.perm().hasPermission(player, "quickshop.bypass." + item.getType().name());
  }

  /**
   * Ouvre (ou rouvre après une erreur) le dialogue de création.
   *
   * @param rachat type déjà choisi (réouverture), null au premier affichage (vente)
   * @param erreur ligne d'erreur à afficher en rouge, ou null
   */
  private static void ouvrir(final QuickShop plugin, final Player player, final SimpleInfo info, final @Nullable Boolean rachat, final @Nullable String erreur) {

    final ItemStack stack = info.getItem();
    final boolean vente = plugin.perm().hasPermission(player, "quickshop.create.sell");
    final boolean achat = plugin.perm().hasPermission(player, "quickshop.create.buy");
    final int quantite = plugin.isAllowStack() && plugin.perm().hasPermission(player, "quickshop.create.stacks")? stack.getAmount() : 1;

    final Component nom = Util.getItemStackName(stack).colorIfAbsent(NamedTextColor.WHITE)
            .append(Component.text(quantite > 1? " x" + quantite : "", NamedTextColor.GRAY));
    final List<DialogBody> corps = new ArrayList<>();
    final ItemStack apercu = stack.clone();
    apercu.setAmount(quantite);
    corps.add(DialogBody.item(apercu).description(DialogBody.plainMessage(nom)).showTooltip(true).build());
    corps.add(DialogBody.plainMessage(MM.deserialize(VIOLET + "Un panneau et l'objet flottant seront posés sur ce coffre.")));
    if(erreur != null) {
      corps.add(DialogBody.plainMessage(MM.deserialize(ROUGE + erreur)));
    }

    final List<DialogInput> champs = new ArrayList<>();
    if(vente && achat) {
      final boolean rachatCoche = rachat != null && rachat;
      champs.add(DialogInput.singleOption("type", MM.deserialize(VIOLET + "Type"), List.of(
              SingleOptionDialogInput.OptionEntry.create("vente", MM.deserialize(OR + "Vente <gray>: les joueurs achètent"), !rachatCoche),
              SingleOptionDialogInput.OptionEntry.create("rachat", MM.deserialize(OR + "Rachat <gray>: les joueurs te vendent"), rachatCoche)
      )).width(260).build());
    }
    final String libellePrix = (quantite > 1? "Prix pour " + quantite + " objets" : "Prix pour 1 objet") + " (" + BITBLOCK + VIOLET + ")";
    champs.add(DialogInput.text("prix", MM.deserialize(VIOLET + libellePrix)).initial("").maxLength(16).width(260).build());

    final ClickCallback.Options une = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
    final ActionButton creer = ActionButton.create(MM.deserialize(VERT + "Créer l'échoppe"), null, 120,
            DialogAction.customClick((vue, audience)->{
              final String type = vue.getText("type");
              final boolean estRachat = vente && achat? "rachat".equals(type) : !vente;
              final String prix = vue.getText("prix");
              Util.regionThread(player.getLocation(), ()->valider(plugin, player, info, estRachat, prix));
            }, une));
    final ActionButton annuler = ActionButton.create(MM.deserialize(ROUGE + "Annuler"), null, 120,
            DialogAction.customClick((vue, audience)->{ }, une));

    final DialogBase base = DialogBase.create(MM.deserialize(OR + "Nouvelle échoppe"), null, true, false,
                                              DialogBase.DialogAfterAction.CLOSE, corps, champs);
    player.showDialog(Dialog.create(f->f.empty().base(base).type(DialogType.confirmation(creer, annuler))));
  }

  /** Validation (fil du joueur) : prix lisible, coffre toujours libre et proche, puis création classique. */
  private static void valider(final QuickShop plugin, final Player player, final SimpleInfo info, final boolean rachat, final @Nullable String saisie) {

    if(!player.isOnline()) {
      return;
    }
    final String prix = saisie == null? "" : saisie.trim().replace(" ", "").replace(" ", "").replace(",", ".");
    if(prix.isEmpty() || prix.startsWith("-") || Util.parse(prix) == null) {
      ouvrir(plugin, player, info, rachat, prix.isEmpty()? "Indique un prix." : "Prix invalide : écris un nombre, par exemple 250 ou 2.5k.");
      return;
    }
    if(info.getLocation().getWorld() != player.getWorld() || info.getLocation().distanceSquared(player.getLocation()) > 25) {
      plugin.text().of(player, "not-looking-at-shop").send();
      return;
    }
    if(plugin.getShopManager().getShop(info.getLocation()) != null) {
      plugin.text().of(player, "shop-already-owned").send();
      return;
    }
    final boolean droit = rachat? plugin.perm().hasPermission(player, "quickshop.create.buy") : plugin.perm().hasPermission(player, "quickshop.create.sell");
    if(!droit) {
      plugin.text().of(player, "no-permission").send();
      return;
    }
    final IShopType type = rachat? BUYING_TYPE : SELLING_TYPE;
    ((SimpleShopManager)plugin.getShopManager()).actionCreate(player, info, prix, type);
  }
}
