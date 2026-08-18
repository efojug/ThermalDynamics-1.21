package cofh.thermal.dynamics.init.data.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static cofh.lib.util.constants.ModIds.ID_THERMAL;

/** Raw JSON provider deliberately free of optional Mekanism registry classes. */
public class TDynChemicalCompatDataProvider implements DataProvider {
    private final PackOutput.PathProvider loot, recipes;
    public TDynChemicalCompatDataProvider(PackOutput output) {
        loot = output.createPathProvider(PackOutput.Target.DATA_PACK, "loot_table");
        recipes = output.createPathProvider(PackOutput.Target.DATA_PACK, "recipe");
    }
    public CompletableFuture<?> run(CachedOutput cache) {
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        tasks.add(DataProvider.saveStable(cache, dropTable("chemical_duct", false), loot.json(ResourceLocation.fromNamespaceAndPath(ID_THERMAL, "blocks/chemical_duct"))));
        tasks.add(DataProvider.saveStable(cache, recipe(), recipes.json(ResourceLocation.fromNamespaceAndPath(ID_THERMAL, "chemical_duct_4"))));
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new));
    }
    public String getName() { return "Thermal Dynamics Chemical Compat Data"; }
    private static JsonObject dropTable(String id, boolean sync) {
        JsonObject entry = new JsonObject(); entry.addProperty("type", "minecraft:item"); entry.addProperty("name", ID_THERMAL + ":" + id);
        if (sync) { JsonArray f = new JsonArray(); JsonObject n = new JsonObject(); n.addProperty("function", "cofh_core:nbt_sync"); f.add(n); entry.add("functions", f); }
        JsonObject c = new JsonObject(); c.addProperty("condition", "minecraft:survives_explosion"); JsonArray cs = new JsonArray(); cs.add(c);
        JsonArray es = new JsonArray(); es.add(entry); JsonObject p = new JsonObject(); p.addProperty("bonus_rolls", 0.0); p.add("conditions", cs); p.add("entries", es); p.addProperty("rolls", 1.0);
        JsonArray ps = new JsonArray(); ps.add(p); JsonObject table = new JsonObject(); table.addProperty("type", "minecraft:block"); table.add("pools", ps); table.addProperty("random_sequence", ID_THERMAL + ":blocks/" + id); return table;
    }
    private static JsonObject recipe() {
        JsonArray conditions = new JsonArray(); JsonObject c = new JsonObject(); c.addProperty("type", "neoforge:mod_loaded"); c.addProperty("modid", "mekanism"); conditions.add(c);
        JsonObject key = new JsonObject(); JsonObject steel = new JsonObject(); steel.addProperty("tag", "c:ingots/steel"); key.add("S", steel); JsonObject glass = new JsonObject(); glass.addProperty("tag", ID_THERMAL + ":glass/hardened"); key.add("G", glass);
        JsonArray pattern = new JsonArray(); pattern.add("SGS"); JsonObject result = new JsonObject(); result.addProperty("count", 4); result.addProperty("id", ID_THERMAL + ":chemical_duct");
        JsonObject r = new JsonObject(); r.add("neoforge:conditions", conditions); r.addProperty("type", "minecraft:crafting_shaped"); r.addProperty("category", "building"); r.add("key", key); r.add("pattern", pattern); r.add("result", result); return r;
    }
}
