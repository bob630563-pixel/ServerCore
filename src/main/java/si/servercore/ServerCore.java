package si.servercore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class ServerCore extends JavaPlugin implements Listener, CommandExecutor {

    private NamespacedKey krampKey;
    private File dataFile;
    private FileConfiguration data;

    static class ShopHolder implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        krampKey = new NamespacedKey(this, "kramp3x3");
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("shop", "sell", "balance", "kramp")) {
            getCommand(c).setExecutor(this);
        }
    }

    // ---------- pomožne metode ----------

    private Component line(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private void msg(CommandSender s, String t, NamedTextColor c) {
        s.sendMessage(Component.text(t, c));
    }

    private String money(double d) {
        return String.format("%.2f €", d);
    }

    private double bal(Player p) {
        return data.getDouble("bal." + p.getUniqueId(), getConfig().getDouble("start-balance", 100));
    }

    private void setBal(Player p, double v) {
        data.set("bal." + p.getUniqueId(), v);
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Ne morem shraniti data.yml: " + ex.getMessage());
        }
    }

    private double buyPrice(Material m) {
        return getConfig().getDouble("items." + m.name() + ".buy", 0);
    }

    private double sellPrice(Material m) {
        return getConfig().getDouble("items." + m.name() + ".sell", 0);
    }

    private boolean isKramp(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(krampKey, PersistentDataType.BYTE);
    }

    private ItemStack kramp() {
        ItemStack it = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta m = it.getItemMeta();
        m.displayName(line("Kramp 3x3", NamedTextColor.GOLD));
        m.addEnchant(Enchantment.EFFICIENCY, 5, true);
        m.getPersistentDataContainer().set(krampKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private void give(Player p, ItemStack it) {
        p.getInventory().addItem(it).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private int removeItems(Player p, Material m, int amount) {
        int left = amount;
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length && left > 0; i++) {
            if (c[i] != null && c[i].getType() == m && !isKramp(c[i])) {
                int t = Math.min(left, c[i].getAmount());
                c[i].setAmount(c[i].getAmount() - t);
                if (c[i].getAmount() <= 0) c[i] = null;
                left -= t;
            }
        }
        p.getInventory().setStorageContents(c);
        return amount - left;
    }

    // ---------- ukazi ----------

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) {
            msg(s, "Samo za igralce.", NamedTextColor.RED);
            return true;
        }
        switch (cmd.getName().toLowerCase()) {
            case "shop" -> openShop(p);
            case "balance" -> msg(p, "Stanje: " + money(bal(p)), NamedTextColor.GREEN);
            case "kramp" -> {
                if (!p.hasPermission("servercore.admin")) {
                    msg(p, "Nimaš dovoljenja. Kramp lahko kupiš v /shop.", NamedTextColor.RED);
                } else {
                    give(p, kramp());
                    msg(p, "Dobil si Kramp 3x3.", NamedTextColor.GOLD);
                }
            }
            case "sell" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("all")) sellAll(p);
                else sellHand(p);
            }
        }
        return true;
    }

    private void sellHand(Player p) {
        ItemStack h = p.getInventory().getItemInMainHand();
        double price = sellPrice(h.getType());
        if (h.getType().isAir() || price <= 0 || isKramp(h)) {
            msg(p, "Tega predmeta ni mogoče prodati.", NamedTextColor.RED);
            return;
        }
        double total = price * h.getAmount();
        int n = h.getAmount();
        p.getInventory().setItemInMainHand(null);
        setBal(p, bal(p) + total);
        msg(p, "Prodano " + n + "x " + h.getType().name() + " za " + money(total), NamedTextColor.GREEN);
    }

    private void sellAll(Player p) {
        ItemStack[] c = p.getInventory().getStorageContents();
        double total = 0;
        for (int i = 0; i < c.length; i++) {
            if (c[i] == null || isKramp(c[i])) continue;
            double price = sellPrice(c[i].getType());
            if (price <= 0) continue;
            total += price * c[i].getAmount();
            c[i] = null;
        }
        if (total <= 0) {
            msg(p, "Nimaš ničesar za prodati.", NamedTextColor.RED);
            return;
        }
        p.getInventory().setStorageContents(c);
        setBal(p, bal(p) + total);
        msg(p, "Prodano vse za " + money(total), NamedTextColor.GREEN);
    }

    // ---------- shop meni ----------

    private void openShop(Player p) {
        ShopHolder h = new ShopHolder();
        Inventory inv = Bukkit.createInventory(h, 54, Component.text("Shop"));
        h.inv = inv;
        ConfigurationSection s = getConfig().getConfigurationSection("items");
        int slot = 0;
        if (s != null) {
            for (String key : s.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null || slot >= 53) continue;
                ItemStack it = new ItemStack(m);
                ItemMeta meta = it.getItemMeta();
                meta.lore(List.of(
                        line("Nakup: " + money(buyPrice(m)), NamedTextColor.GREEN),
                        line("Prodaja: " + money(sellPrice(m)), NamedTextColor.YELLOW),
                        line("Levi klik: kupi 1 | Shift+levi: kupi 64", NamedTextColor.GRAY),
                        line("Desni klik: prodaj 1 | Shift+desni: prodaj 64", NamedTextColor.GRAY)));
                it.setItemMeta(meta);
                inv.setItem(slot++, it);
            }
        }
        ItemStack k = kramp();
        ItemMeta km = k.getItemMeta();
        km.lore(List.of(line("Cena: " + money(getConfig().getDouble("kramp-price", 5000)), NamedTextColor.GREEN),
                line("Kopa 3x3 bloke.", NamedTextColor.GRAY)));
        k.setItemMeta(km);
        inv.setItem(53, k);
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType().isAir()) return;

        if (e.getSlot() == 53) {
            double price = getConfig().getDouble("kramp-price", 5000);
            if (bal(p) < price) {
                msg(p, "Premalo denarja (" + money(price) + ").", NamedTextColor.RED);
                return;
            }
            setBal(p, bal(p) - price);
            give(p, kramp());
            msg(p, "Kupil si Kramp 3x3.", NamedTextColor.GOLD);
            return;
        }

        Material m = it.getType();
        int amount = e.isShiftClick() ? 64 : 1;
        if (e.isLeftClick()) {
            double price = buyPrice(m) * amount;
            if (buyPrice(m) <= 0) {
                msg(p, "Tega ni mogoče kupiti.", NamedTextColor.RED);
            } else if (bal(p) < price) {
                msg(p, "Premalo denarja (" + money(price) + ").", NamedTextColor.RED);
            } else {
                setBal(p, bal(p) - price);
                give(p, new ItemStack(m, amount));
                msg(p, "Kupljeno " + amount + "x " + m.name() + " za " + money(price), NamedTextColor.GREEN);
            }
        } else if (e.isRightClick()) {
            if (sellPrice(m) <= 0) {
                msg(p, "Tega ni mogoče prodati.", NamedTextColor.RED);
                return;
            }
            int sold = removeItems(p, m, amount);
            if (sold == 0) {
                msg(p, "Nimaš tega predmeta.", NamedTextColor.RED);
                return;
            }
            double total = sellPrice(m) * sold;
            setBal(p, bal(p) + total);
            msg(p, "Prodano " + sold + "x " + m.name() + " za " + money(total), NamedTextColor.GREEN);
        }
    }

    // ---------- kramp 3x3 ----------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (!isKramp(tool)) return;
        BlockFace face = p.getTargetBlockFace(6);
        if (face == null) return;

        Block center = e.getBlock();
        int broken = 0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                if (i == 0 && j == 0) continue;
                int x = 0, y = 0, z = 0;
                switch (face) {
                    case UP, DOWN -> { x = i; z = j; }
                    case NORTH, SOUTH -> { x = i; y = j; }
                    default -> { y = i; z = j; }
                }
                Block t = center.getRelative(x, y, z);
                Material tm = t.getType();
                if (tm.isAir() || tm.getHardness() < 0 || !t.isPreferredTool(tool)) continue;
                t.breakNaturally(tool);
                broken++;
            }
        }
        if (broken > 0) tool.damage(broken, p);
    }
}
