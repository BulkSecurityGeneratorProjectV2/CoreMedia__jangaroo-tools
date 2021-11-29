import PropertiesTest_properties from "./PropertiesTest_properties";
import ResourceBundleUtil from "@jangaroo/runtime/l10n/ResourceBundleUtil";

/**
 * Overrides of ResourceBundle "PropertiesTest" for Locale "de".
 * @see PropertiesTest_properties#INSTANCE
 */
ResourceBundleUtil.override(PropertiesTest_properties, {
 key: "Die Platte \"{1}\" enthält {0}.",
 madeUp: "Das hier gibt es nur auf Deutsch."
});
