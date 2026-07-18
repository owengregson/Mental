package me.vexmc.mental.v5.config.settings;

/**
 * The settings for a feature whose only configuration is its module toggle —
 * every such rule/consumable/sustain feature carries
 * no tunables of its own (the era values ARE the behavior). Each such feature
 * still owns a distinct {@code SettingsKey<NoSettings>} identity; only the
 * value shape is shared. Enablement lives on the module toggle
 * ({@code Snapshot.enabled(Feature)}), never here.
 */
public record NoSettings() {

    public static final NoSettings DEFAULTS = new NoSettings();
}
