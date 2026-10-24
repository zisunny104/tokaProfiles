package dev.toka.profiles;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementButtonImageData;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Shows forms and routes each answer to the code that asked, by the form id. One form per player is open at a time. */
final class Forms implements Listener {
    /** A button: its text, an icon path inside the game (null for none) and what it does. */
    record Item(String label, String icon, Consumer<Player> action) {
        static Item of(String label, String icon, Consumer<Player> action) { return new Item(label, icon, action); }
    }

    private record Pending(int id, Consumer<PlayerFormRespondedEvent> handler) {}

    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private void show(Player player, FormWindow window, Consumer<PlayerFormRespondedEvent> handler) {
        pending.put(player.getUniqueId(), new Pending(player.showFormWindow(window), handler));
    }

    void menu(Player player, String title, String body, List<Item> items) {
        FormWindowSimple form = new FormWindowSimple(title, body == null ? "" : body);
        for (Item item : items) {
            form.addButton(item.icon() == null ? new ElementButton(item.label())
                    : new ElementButton(item.label(), new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_PATH, item.icon())));
        }
        show(player, form, event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseSimple response)) return;
            int id = response.getClickedButtonId();
            if (id >= 0 && id < items.size() && items.get(id).action() != null) items.get(id).action().accept(player);
        });
    }

    /** @param closed called when the player closes the form without answering */
    void custom(Player player, FormWindowCustom form, Consumer<FormResponseCustom> submitted, Consumer<Player> closed) {
        show(player, form, event -> {
            if (event.wasClosed() || !(event.getResponse() instanceof FormResponseCustom response)) closed.accept(player);
            else submitted.accept(response);
        });
    }

    void confirm(Player player, String title, String content, String yes, String no, Consumer<Player> accepted, Consumer<Player> declined) {
        show(player, new FormWindowModal(title, content, yes, no), event -> {
            if (!event.wasClosed() && event.getResponse() instanceof FormResponseModal response && response.getClickedButtonId() == 0) accepted.accept(player);
            else declined.accept(player);
        });
    }

    static String input(FormResponseCustom response, int element) {
        String value = response.getInputResponse(element);
        return value == null ? "" : value.strip();
    }

    @EventHandler public void onForm(PlayerFormRespondedEvent event) {
        Pending form = pending.get(event.getPlayer().getUniqueId());
        if (form == null || form.id() != event.getFormID()) return;
        pending.remove(event.getPlayer().getUniqueId());
        form.handler().accept(event);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { pending.remove(event.getPlayer().getUniqueId()); }
}
