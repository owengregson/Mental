package me.vexmc.mental.v5.feature;

/**
 * A typed settings identity — one per feature, the key under which the
 * {@code Snapshot} stores that feature's parsed settings record. Identity is
 * by object reference (the enum constant that holds it), never by name or
 * type: two features with the same settings <em>type</em> (several toggle-only
 * features share {@code NoSettings}) still hold distinct keys, so a lookup can
 * never return another feature's settings. The {@code Class<S>} pins the value
 * type for {@code Snapshot.settings(key)}.
 */
public final class SettingsKey<S> {

    private final String name;
    private final Class<S> type;

    public SettingsKey(String name, Class<S> type) {
        this.name = name;
        this.type = type;
    }

    public String name() {
        return name;
    }

    public Class<S> type() {
        return type;
    }

    // equals/hashCode are Object identity by design — see the class doc.

    @Override
    public String toString() {
        return "SettingsKey[" + name + " : " + type.getSimpleName() + "]";
    }
}
