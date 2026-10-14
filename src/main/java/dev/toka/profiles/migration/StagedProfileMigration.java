package dev.toka.profiles.migration;

import com.google.gson.*;
import dev.toka.storage.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** 獨立資料集的背景搬遷。ready 僅代表快照驗證通過，不會切換正式資料來源。 */
public final class StagedProfileMigration {
    private StagedProfileMigration() {}
    public static void main(String[] args)throws Exception {
        if(args.length<1)throw new IllegalArgumentException("stage SOURCE BACKEND STAGE [BATCH DELAY_MS PLAN_EVIDENCE.json] | verify STAGE CURRENT_SOURCE BACKEND");
        if(args[0].equals("verify")&&args.length==4){verifyReady(Path.of(args[1]),Path.of(args[2]),Backend.valueOf(args[3].toUpperCase(Locale.ROOT)));System.out.println("STAGE_VERIFY_PASS NoActivationPerformed=true");return;}
        if(!args[0].equals("stage")||(args.length!=4&&args.length!=6&&args.length!=7))throw new IllegalArgumentException("Invalid staged migration arguments");
        int batch=args.length>=6?Integer.parseInt(args[4]):50;long delay=args.length>=6?Long.parseLong(args[5]):100;
        JsonArray evidence=args.length==7?JsonParser.parseString(Files.readString(Path.of(args[6]))).getAsJsonArray():new JsonArray();
        Result r=startAsync(Path.of(args[1]),Backend.valueOf(args[2].toUpperCase(Locale.ROOT)),Path.of(args[3]),batch,delay,evidence).get();
        System.out.println("STAGE_RESULT Status="+r.status()+" Source="+r.sourceRows()+" Accepted="+r.acceptedRows()+" Conflicts="+r.conflicts()+" Completed="+r.completed()+" NoActivationPerformed=true");
    }
    public record Result(String status,int sourceRows,int acceptedRows,int conflicts,int completed,Path stage) {}
    public static CompletableFuture<Result> startAsync(Path source,Backend backend,Path stage,int batchSize,long delayMillis) {
        return startAsync(source,backend,stage,batchSize,delayMillis,new JsonArray());
    }
    public static CompletableFuture<Result> startAsync(Path source,Backend backend,Path stage,int batchSize,long delayMillis,JsonArray planEvidence) {
        JsonArray evidence=planEvidence.deepCopy();
        ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"tokaProfiles-staged-migration");t.setDaemon(true);return t;});
        CompletableFuture<Result> result=CompletableFuture.supplyAsync(()->{
            try { return run(source,backend,stage,batchSize,delayMillis,evidence); }
            catch(Exception error) { throw new CompletionException(error); }
        },worker);
        result.whenComplete((value,error)->{if(result.isCancelled())worker.shutdownNow();else worker.shutdown();});
        return result;
    }
    public static Result run(Path source,Backend backend,Path stage,int batchSize,long delayMillis,JsonArray evidence)throws Exception {
        if(batchSize<1||batchSize>1000||delayMillis<0||delayMillis>60000)throw new IllegalArgumentException("Invalid batch limits");
        source=source.toRealPath();stage=stage.toAbsolutePath().normalize();
        if(source.startsWith(stage))throw new IllegalArgumentException("Source snapshot must be outside stage");
        byte[] original=readSource(source);String sourceHash=hash(original);
        IdentityAudit.Result audit=IdentityAudit.inspect(rows(original),evidence);
        SortedMap<String,JsonObject> expected=ProfileMigration.prepare(audit.accepted());
        Files.createDirectories(stage); privateDirectory(stage);
        try(FileChannel channel=FileChannel.open(stage.resolve("migration.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("Another staged migration is running");
            Path manifestPath=stage.resolve("manifest.json");JsonObject manifest;
            int completed=0;
            String evidenceHash=hash(evidence.toString().getBytes(StandardCharsets.UTF_8));
            if(Files.exists(manifestPath)) {
                manifest=JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
                if(manifest.get("schema").getAsInt()!=1||!sourceHash.equals(manifest.get("sourceSha256").getAsString())
                    ||!backend.name().equals(manifest.get("backend").getAsString())||!evidenceHash.equals(manifest.get("planEvidenceSha256").getAsString()))
                    throw new IllegalStateException("Source, backend or evidence changed; use a new stage");
                completed=manifest.get("completed").getAsInt();
                if(completed<0||completed>expected.size())throw new IllegalStateException("Invalid checkpoint");
                if(!Arrays.equals(original,Files.readAllBytes(stage.resolve("source-snapshot.json"))))throw new IllegalStateException("Source backup mismatch");
            } else {
                if(Files.exists(stage.resolve("data"))||Files.exists(stage.resolve("source-snapshot.json")))throw new IllegalStateException("Unrecognized existing stage");
                Files.write(stage.resolve("source-snapshot.json"),original,StandardOpenOption.CREATE_NEW);
                manifest=new JsonObject();manifest.addProperty("schema",1);manifest.addProperty("sourceSha256",sourceHash);
                manifest.addProperty("planEvidenceSha256",evidenceHash);manifest.addProperty("backend",backend.name());
                manifest.addProperty("sourceRows",audit.total());manifest.addProperty("acceptedRows",expected.size());manifest.addProperty("conflictRows",audit.conflicts().size());
                manifest.addProperty("completed",0);manifest.addProperty("activation","explicit-next-restart-only");
                manifest.addProperty("status","staging");atomicJson(manifestPath,manifest);
            }
            atomicJson(stage.resolve("identity-audit.json"),audit.report());
            Files.writeString(stage.resolve("NOTICE.txt"),"私人搬遷快照。不可加入 Git。ready 不代表正式資料已切換；線上來源仍須停服後補齊差異。\n",StandardCharsets.UTF_8);
            List<Map.Entry<String,JsonObject>> entries=new ArrayList<>(expected.entrySet());
            try(DocumentStore store=DocumentStore.open(new StoreConfig(backend,stage.resolve("data"),1))) {
                Map<String,JsonObject> existing=store.snapshot();
                for(var item:existing.entrySet())if(!item.getValue().equals(expected.get(item.getKey())))throw new IllegalStateException("Unexpected staged profile");
                for(int i=0;i<completed;i++)if(!entries.get(i).getValue().equals(existing.get(entries.get(i).getKey())))throw new IllegalStateException("Checkpoint data missing");
                while(completed<entries.size()) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("Migration interrupted; checkpoint retained");
                    int end=Math.min(completed+batchSize,entries.size());Map<String,JsonObject> batch=new LinkedHashMap<>();
                    for(int i=completed;i<end;i++)batch.put(entries.get(i).getKey(),entries.get(i).getValue());
                    store.saveAll(batch);
                    completed=end;manifest.addProperty("completed",completed);manifest.addProperty("status","staging");atomicJson(manifestPath,manifest);
                    if(completed<entries.size()&&delayMillis>0)Thread.sleep(delayMillis);
                }
            }
            try(DocumentStore reopened=DocumentStore.open(new StoreConfig(backend,stage.resolve("data"),1))) {
                if(!expected.equals(reopened.snapshot()))throw new IllegalStateException("Staged readback mismatch");
            }
            if(!sourceHash.equals(hash(readSource(source))))throw new IllegalStateException("Source changed during migration; stage cannot activate");
            String status=audit.ready()?"ready":"blocked-conflicts";
            manifest.addProperty("status",status);manifest.addProperty("verified",true);atomicJson(manifestPath,manifest);
            return new Result(status,audit.total(),expected.size(),audit.conflicts().size(),completed,stage);
        }
    }
    /** 重啟入口必須提供目前的來源快照；有新變更、衝突或資料毀損即拒絕。 */
    public static Path verifyReady(Path stage,Path currentSource,Backend backend)throws Exception {
        stage=stage.toAbsolutePath().normalize();
        try(FileChannel channel=FileChannel.open(stage.resolve("migration.lock"),StandardOpenOption.WRITE);FileLock lock=channel.tryLock()) {
            if(lock==null)throw new IllegalStateException("Migration is still running");
            JsonObject manifest=JsonParser.parseString(Files.readString(stage.resolve("manifest.json"))).getAsJsonObject();
            if(manifest.get("schema").getAsInt()!=1||!"ready".equals(manifest.get("status").getAsString())
                ||!manifest.get("verified").getAsBoolean()||manifest.get("conflictRows").getAsInt()!=0
                ||!backend.name().equals(manifest.get("backend").getAsString()))throw new IllegalStateException("Stage is not ready for activation");
            byte[] source=readSource(currentSource);String wanted=manifest.get("sourceSha256").getAsString();
            if(!wanted.equals(hash(source))||!Arrays.equals(source,Files.readAllBytes(stage.resolve("source-snapshot.json"))))throw new IllegalStateException("Source changed; catch up in a new stage before activation");
            IdentityAudit.Result audit=IdentityAudit.inspect(rows(source));
            if(!audit.ready())throw new IllegalStateException("Unresolved identity conflicts");
            SortedMap<String,JsonObject> expected=ProfileMigration.prepare(audit.accepted());
            try(DocumentStore store=DocumentStore.open(new StoreConfig(backend,stage.resolve("data"),1))) {
                if(!expected.equals(store.snapshot()))throw new IllegalStateException("Activation readback mismatch");
            }
            if(manifest.get("completed").getAsInt()!=expected.size()||manifest.get("sourceRows").getAsInt()!=audit.total())throw new IllegalStateException("Activation count mismatch");
            return stage.resolve("data");
        }
    }
    public static JsonArray rows(byte[] original) {
        JsonElement parsed=JsonParser.parseString(new String(original,StandardCharsets.UTF_8));
        if(parsed.isJsonArray())return parsed.getAsJsonArray();
        JsonObject object=parsed.getAsJsonObject();
        if(!object.has("schema")||!"1".equals(object.get("schema").getAsString())||!"playerData".equals(object.get("table").getAsString()))throw new IllegalArgumentException("Unknown source format");
        return object.getAsJsonArray("rows");
    }
    private static byte[] readSource(Path source)throws Exception {
        if(!Files.isRegularFile(source)||Files.size(source)>64L*1024*1024)throw new IllegalArgumentException("Source must be a JSON snapshot under 64 MiB");
        return Files.readAllBytes(source);
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void atomicJson(Path path,JsonObject value)throws Exception {
        Path temporary=path.resolveSibling("."+path.getFileName()+"."+UUID.randomUUID());
        Files.writeString(temporary,value.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    private static void privateDirectory(Path directory)throws Exception {
        if(Files.getFileStore(directory).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
}
