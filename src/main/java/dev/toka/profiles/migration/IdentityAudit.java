package dev.toka.profiles.migration;

import com.google.gson.*;
import java.util.*;

/** Plan 僅提供交叉核對證據；不自動推定身分、合併資料或授予權限。 */
public final class IdentityAudit {
    private IdentityAudit() {}
    public record Result(JsonArray accepted, JsonArray conflicts, JsonArray planEvidence, JsonArray conflictGroups, int total) {
        public boolean ready() { return conflicts.isEmpty(); }
        public JsonObject report() {
            JsonObject result = new JsonObject();
            result.addProperty("schema", 1);
            result.addProperty("sourceRows", total);
            result.addProperty("acceptedRows", accepted.size());
            result.addProperty("conflictRows", conflicts.size());
            result.addProperty("automaticMerge", false);
            result.addProperty("planIsAdvisory", true);
            result.add("conflicts", conflicts.deepCopy());
            result.add("planEvidence", planEvidence.deepCopy());
            result.add("conflictGroups", conflictGroups.deepCopy());
            return result;
        }
    }
    public static Result inspect(JsonArray rows) { return inspect(rows, new JsonArray()); }
    /** Plan 輸入為已匯出的 uuid/name 陣列，不會連線或寫入 Plan。 */
    public static Result inspect(JsonArray rows, JsonArray planRows) {
        List<LinkedHashSet<String>> reasons = new ArrayList<>();
        Map<String,List<Integer>> uuids = new HashMap<>(), names = new HashMap<>(), ids = new HashMap<>();
        List<String> parsedUuids = new ArrayList<>(), parsedNames = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            reasons.add(new LinkedHashSet<>());
            String uuid = null, name = null;
            if (!rows.get(i).isJsonObject()) reasons.get(i).add("INVALID_ROW");
            else {
                JsonObject row = rows.get(i).getAsJsonObject();
                uuid = canonicalUuid(string(row, "UUID"));
                if (uuid == null) reasons.get(i).add("MISSING_OR_INVALID_UUID");
                name = string(row, "name");
                if (name == null || name.isBlank() || !name.equals(name.trim())) reasons.get(i).add("INVALID_NAME");
                else add(names, name.toLowerCase(Locale.ROOT), i);
                if (uuid != null) add(uuids, uuid, i);
                try {
                    String id = row.get("id").getAsBigDecimal().toBigIntegerExact().toString();
                    if (new java.math.BigInteger(id).signum() < 0) throw new IllegalArgumentException();
                    add(ids, id, i);
                } catch (RuntimeException error) { reasons.get(i).add("INVALID_LEGACY_ID"); }
                try { JsonArray singleton = new JsonArray(); singleton.add(row.deepCopy()); ProfileMigration.prepare(singleton); }
                catch (RuntimeException error) { reasons.get(i).add("INVALID_PROFILE_FIELDS"); }
            }
            parsedUuids.add(uuid); parsedNames.add(name);
        }
        duplicates(uuids, reasons, "DUPLICATE_UUID_REQUIRES_REVIEW");
        duplicates(names, reasons, "AMBIGUOUS_NAME_REQUIRES_REVIEW");
        duplicates(ids, reasons, "DUPLICATE_LEGACY_ID_REQUIRES_REVIEW");
        Map<String,Set<String>> planByName = new HashMap<>(), planByUuid = new HashMap<>();
        for (JsonElement element : planRows) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Invalid Plan evidence row");
            JsonObject row = element.getAsJsonObject();
            String uuid = canonicalUuid(string(row,"uuid")), name = string(row,"name");
            if (uuid == null || name == null || name.isBlank()) throw new IllegalArgumentException("Invalid Plan evidence identity");
            planByName.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new TreeSet<>()).add(uuid);
            planByUuid.computeIfAbsent(uuid, k -> new TreeSet<>()).add(name);
        }
        JsonArray accepted = new JsonArray(), conflicts = new JsonArray(), evidence = new JsonArray();
        for (int i = 0; i < rows.size(); i++) {
            String uuid = parsedUuids.get(i), name = parsedNames.get(i);
            Set<String> candidates = name == null ? Set.of() : planByName.getOrDefault(name.toLowerCase(Locale.ROOT),Set.of());
            if (uuid != null && !candidates.isEmpty() && (candidates.size() != 1 || !candidates.contains(uuid)))
                reasons.get(i).add("PLAN_NAME_UUID_DISAGREEMENT");
            if (!candidates.isEmpty() || uuid != null && planByUuid.containsKey(uuid)) {
                JsonObject item = new JsonObject(); item.addProperty("rowIndex",i);
                JsonArray matches = new JsonArray(); candidates.forEach(matches::add); item.add("nameMatchedUuids",matches);
                JsonArray aliases = new JsonArray(); planByUuid.getOrDefault(uuid,Set.of()).forEach(aliases::add); item.add("uuidMatchedNames",aliases);
                item.addProperty("automaticallyApplied",false); evidence.add(item);
            }
            if (reasons.get(i).isEmpty()) accepted.add(rows.get(i).deepCopy());
            else {
                JsonObject item = new JsonObject(); item.addProperty("rowIndex", i);
                if (rows.get(i).isJsonObject()) {
                    JsonObject row = rows.get(i).getAsJsonObject();
                    for (String field : List.of("id","name","UUID")) if (row.has(field)) item.add(field,row.get(field).deepCopy());
                }
                JsonArray causes = new JsonArray(); reasons.get(i).forEach(causes::add); item.add("reasons",causes);
                item.addProperty("legacyAuthorityRetained",true); conflicts.add(item);
            }
        }
        JsonArray groups=new JsonArray();
        describeGroups("uuid",uuids,rows,groups);describeGroups("name",names,rows,groups);describeGroups("legacyId",ids,rows,groups);
        return new Result(accepted,conflicts,evidence,groups,rows.size());
    }
    private static void describeGroups(String kind,Map<String,List<Integer>> index,JsonArray rows,JsonArray output){
        for(var entry:new TreeMap<>(index).entrySet())if(entry.getValue().size()>1){
            JsonObject group=new JsonObject();group.addProperty("kind",kind);group.addProperty("key",entry.getKey());
            JsonArray members=new JsonArray();entry.getValue().forEach(members::add);group.add("rowIndices",members);
            var fields=new TreeSet<String>();for(int i:entry.getValue())fields.addAll(rows.get(i).getAsJsonObject().keySet());
            JsonArray differences=new JsonArray();
            for(String field:fields){JsonElement first=rows.get(entry.getValue().get(0)).getAsJsonObject().get(field);for(int i:entry.getValue())if(!Objects.equals(first,rows.get(i).getAsJsonObject().get(field))){differences.add(field);break;}}
            group.add("differentFields",differences);boolean sameBusiness=true;for(JsonElement field:differences)if(!field.getAsString().equals("id"))sameBusiness=false;
            group.addProperty("identicalExceptLegacyId",sameBusiness);group.addProperty("requiresReview",true);group.addProperty("automaticallyMerged",false);output.add(group);
        }
    }
    private static void add(Map<String,List<Integer>> index,String key,int row) { index.computeIfAbsent(key,k->new ArrayList<>()).add(row); }
    private static void duplicates(Map<String,List<Integer>> index,List<LinkedHashSet<String>> reasons,String code) {
        for (List<Integer> rows : index.values()) if (rows.size()>1) for (int row : rows) reasons.get(row).add(code);
    }
    private static String string(JsonObject row,String key) {
        JsonElement value = row.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }
    private static String canonicalUuid(String raw) {
        try { UUID uuid = UUID.fromString(raw); return uuid.toString().equalsIgnoreCase(raw) ? uuid.toString() : null; }
        catch (RuntimeException error) { return null; }
    }
}
