package dev.toka.profiles.migration;

import com.google.gson.*;
import dev.toka.profiles.model.Profile;
import dev.toka.profiles.storage.*;
import dev.toka.storage.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Offline import only. Full legacy rows remain in a private backup, never in the public API. */
public final class ProfileMigration {
    public record Result(int profiles,Path backup,Path destination) {}
    public static SortedMap<String,JsonObject> prepare(JsonArray rows) {
        var documents=new TreeMap<String,JsonObject>();var ids=new HashSet<String>();var names=new HashSet<String>();
        for(JsonElement element:rows) {
            JsonObject row=element.getAsJsonObject();String name=row.get("name").getAsString();
            String raw=row.get("UUID").getAsString();UUID uuid=UUID.fromString(raw);
            if(!uuid.toString().equalsIgnoreCase(raw))throw new IllegalArgumentException("Noncanonical or missing UUID");
            String id=row.get("id").getAsBigDecimal().toBigIntegerExact().toString();
            if(!ids.add(id)||!names.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Duplicate legacy identity");
            var prefs=new TreeMap<String,String>();
            if(row.has("FirstWindow")&&!row.get("FirstWindow").isJsonNull())
                prefs.put("firstWindowDate",row.get("FirstWindow").getAsBigDecimal().toBigIntegerExact().toString());
            String title=row.has("title")&&!row.get("title").isJsonNull()?row.get("title").getAsString():"";
            Profile profile=new Profile(uuid,name,Set.of(),prefs,id,title);
            if(documents.putIfAbsent(ProfileCodec.key(uuid),ProfileCodec.encode(profile))!=null)
                throw new IllegalArgumentException("Duplicate UUID");
        }
        return documents;
    }
    public static Result importFile(Path source,Backend backend,Path destination,Path backups)throws Exception {
        source=source.toRealPath();destination=destination.toAbsolutePath().normalize();backups=backups.toAbsolutePath().normalize();
        if(!Files.isRegularFile(source)||Files.size(source)>64L*1024*1024)throw new IllegalArgumentException("Source must be a JSON snapshot under 64 MiB");
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(destination.toString());
        if(destination.equals(source)||backups.startsWith(destination))throw new IllegalArgumentException("Independent destination and backup required");
        byte[] original=Files.readAllBytes(source);
        JsonElement parsed=JsonParser.parseString(new String(original,StandardCharsets.UTF_8));
        if(parsed.isJsonObject()&&(!parsed.getAsJsonObject().get("schema").getAsString().equals("1")||!parsed.getAsJsonObject().get("table").getAsString().equals("playerData")))throw new IllegalArgumentException("Unknown export format");
        JsonArray rows=parsed.isJsonArray()?parsed.getAsJsonArray():parsed.getAsJsonObject().getAsJsonArray("rows");
        var expected=prepare(rows);Files.createDirectories(backups);
        Path backup=backups.resolve("legacy-playerData-"+UUID.randomUUID()+".json");
        Files.write(backup,original,StandardOpenOption.CREATE_NEW);
        if(!Arrays.equals(original,Files.readAllBytes(backup)))throw new IllegalStateException("Backup mismatch");
        Files.createDirectories(destination.getParent());
        Path stage=destination.resolveSibling("."+destination.getFileName()+".import-"+UUID.randomUUID());
        try {
            try(DocumentStore store=DocumentStore.open(new StoreConfig(backend,stage,1))){store.saveAll(expected);}
            try(ProfileRepository store=ProfileRepository.open(new StoreConfig(backend,stage,1),16)) {
                if(store.snapshot().size()!=expected.size())throw new IllegalStateException("Count mismatch");
                for(Profile profile:store.snapshot().values())
                    if(!ProfileCodec.encode(profile).equals(expected.get(ProfileCodec.key(profile.uuid()))))throw new IllegalStateException("Profile mismatch");
            }
            if(!Arrays.equals(original,Files.readAllBytes(source)))throw new IllegalStateException("Source changed during migration");
            Files.move(stage,destination);
            JsonObject receipt=new JsonObject();receipt.addProperty("profiles",expected.size());
            receipt.addProperty("sourceSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
            receipt.addProperty("backup",backup.toString());receipt.addProperty("backend",backend.name());
            Files.writeString(backups.resolve("receipt-"+UUID.randomUUID()+".json"),receipt.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            return new Result(expected.size(),backup,destination);
        }catch(Exception error){if(Files.isDirectory(stage))Files.writeString(stage.resolve("IMPORT_FAILED.txt"),"Do not activate; original source retained.");throw error;}
    }
    public static void main(String[] args)throws Exception {
        if(args.length==4&&args[0].equals("export-jdbc")){exportJdbc(args[1],args[2],Path.of(args[3]));return;}
        if(args.length==3&&args[0].equals("plan-permissions")){
            JsonElement source=JsonParser.parseString(Files.readString(Path.of(args[1]),StandardCharsets.UTF_8));
            JsonArray rows=source.isJsonArray()?source.getAsJsonArray():source.getAsJsonObject().getAsJsonArray("rows");
            prepare(rows);String plan=permissionPlan(rows);Files.writeString(Path.of(args[2]),plan,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            System.out.println("PERMISSION_PLAN_PASS Rows="+rows.size()+" ReviewBeforeApplying=true");return;
        }
        if(args.length!=5||!args[0].equals("import"))throw new IllegalArgumentException("Stop server first: import SOURCE.json sqlite|json|yaml NEW_DIRECTORY BACKUPS");
        Result result=importFile(Path.of(args[1]),Backend.valueOf(args[2].toUpperCase(Locale.ROOT)),Path.of(args[3]),Path.of(args[4]));
        System.out.println("PROFILE_IMPORT_PASS Profiles="+result.profiles()+" Destination="+result.destination()+" Backup="+result.backup());
    }
    public static String permissionPlan(JsonArray rows) {
        StringBuilder plan=new StringBuilder("# UNREVIEWED identity groups only. Administrator must approve capability mapping. No wildcard, admin, trusted or OP grants.\n");
        plan.append("lp creategroup toka-player\n");
        for(String permission:List.of("plan.command","plan.player.self","plan.ingame.self","plan.register.self","plan.unregister.self","plan.webgroup.player"))
            plan.append("lp group toka-player permission set ").append(permission).append(" true\n");
        for(int rank:new int[]{0,1,2,3,4,8}) {
            String group="toka-qx-"+rank;plan.append("lp creategroup ").append(group).append('\n');
            plan.append("lp group ").append(group).append(" parent add toka-player\n");



        }
        for(JsonElement element:rows) {
            JsonObject row=element.getAsJsonObject();int rank=row.get("qx").getAsBigDecimal().intValueExact();
            if(!Set.of(0,1,2,3,4,8).contains(rank))throw new IllegalArgumentException("Unknown qx; inspect source before mapping");
            UUID uuid=UUID.fromString(row.get("UUID").getAsString());
            plan.append("lp user ").append(uuid).append(" parent add toka-qx-").append(rank).append('\n');
        }
        return plan.toString();
    }
    private static void exportJdbc(String url,String user,Path destination)throws Exception {
        if(!url.startsWith("jdbc:mysql:")||url.toLowerCase(Locale.ROOT).contains("password="))throw new IllegalArgumentException("Use a MySQL URL without credentials");
        destination=destination.toAbsolutePath().normalize();if(Files.exists(destination))throw new FileAlreadyExistsException(destination.toString());
        String password=System.getenv("TOKA_DB_PASSWORD");
        if(password==null){var console=System.console();if(console==null)throw new IllegalArgumentException("Provide TOKA_DB_PASSWORD or run from an interactive console");char[] secret=console.readPassword("資料庫密碼：");password=new String(secret);Arrays.fill(secret,'\0');}
        JsonArray rows=new JsonArray();
        try(var connection=java.sql.DriverManager.getConnection(url,user,password)) {
            connection.setReadOnly(true);connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
            try(var statement=connection.createStatement();var result=statement.executeQuery("SELECT * FROM playerData ORDER BY id")) {
                var metadata=result.getMetaData();while(result.next()){
                    if(rows.size()>=100000)throw new IllegalStateException("Export exceeds 100000 profiles; use a streaming adapter");
                    JsonObject row=new JsonObject();for(int i=1;i<=metadata.getColumnCount();i++){
                        Object value=result.getObject(i);String key=metadata.getColumnLabel(i);
                        if(value==null)row.add(key,JsonNull.INSTANCE);else if(value instanceof String s)row.addProperty(key,s);
                        else if(value instanceof Number n)row.addProperty(key,n);else if(value instanceof Boolean b)row.addProperty(key,b);
                        else throw new IllegalStateException("Unsupported SQL column type: "+key);
                    }rows.add(row);
                }
            }finally{connection.rollback();}
        }
        JsonObject export=new JsonObject();export.addProperty("schema",1);export.addProperty("table","playerData");export.add("rows",rows);
        Files.createDirectories(destination.getParent());Files.writeString(destination,export.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        if(!JsonParser.parseString(Files.readString(destination)).equals(export))throw new IllegalStateException("Export readback mismatch");
        System.out.println("PROFILE_EXPORT_PASS Rows="+rows.size()+" Destination="+destination);
    }
}
