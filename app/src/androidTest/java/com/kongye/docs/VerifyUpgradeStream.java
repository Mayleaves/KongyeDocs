package com.kongye.docs;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** On-device streaming comparison of fresh canonical v2 exports. No document text is printed. */
public final class VerifyUpgradeStream {
    static class Snapshot {
        final Map<String,String> fields=new TreeMap<>();
        int docs,images,versions,templates,exports;
    }
    static String hex(byte[] bytes) {
        StringBuilder out=new StringBuilder();
        for(byte b:bytes) out.append(String.format(Locale.ROOT,"%02x",b&255));
        return out.toString();
    }
    static void require(boolean ok,String message) { if(!ok) throw new IllegalStateException(message); }
    static void checksum(File file) throws Exception {
        // BackupCodec.encode writes checksum last; unsigned canonical JSON differs
        // only by these 64 ASCII characters, so it can be verified without a JSON tree.
        try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
            int size=(int)Math.min(512,input.length());
            byte[] tail=new byte[size]; input.seek(input.length()-size); input.readFully(tail);
            String end=new String(tail,StandardCharsets.US_ASCII);
            Matcher matcher=Pattern.compile("\\\"checksum\\\":\\\"([0-9a-f]{64})\\\"[}]\\s*$").matcher(end);
            require(matcher.find(),"Missing canonical v2 checksum");
            String expected=matcher.group(1);
            long skip=input.length()-size+matcher.start(1);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[65536]; input.seek(0);
            long remaining=skip;
            while(remaining>0) { int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining)); require(n>0,"Truncated export"); digest.update(buffer,0,n); remaining-=n; }
            input.seek(skip+64);
            int n; while((n=input.read(buffer))!=-1) digest.update(buffer,0,n);
            require(hex(digest.digest()).equals(expected),"Export checksum mismatch");
        }
    }
    static void token(MessageDigest digest,char type,String text) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        digest.update((byte)type);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte)':'); digest.update(bytes);
    }
    static int value(JsonReader reader,MessageDigest digest) throws Exception { return value(reader,digest,false); }
    static int value(JsonReader reader,MessageDigest digest,boolean legacyTimes) throws Exception { return value(reader,digest,legacyTimes,false); }
    static int value(JsonReader reader,MessageDigest digest,boolean legacyTimes,boolean volatileMetadata) throws Exception {
        int count=0;
        switch(reader.peek()) {
            case BEGIN_OBJECT:
                digest.update((byte)'{'); reader.beginObject();
                Set<String> keys=new HashSet<>();
                while(reader.hasNext()) {
                    String key=reader.nextName(); require(keys.add(key),"Duplicate JSON key");
                    if(legacyTimes && key.equals("time") || volatileMetadata && (key.equals("openedAt") || key.equals("revision"))) { reader.skipValue(); continue; }
                    token(digest,'K',key);
                    if(key.equals("kind") && reader.peek()==JsonToken.STRING) {
                        String kind=reader.nextString(); require(!kind.equals("HISTORY"),"Discontinued history exported"); token(digest,'S',kind);
                    } else value(reader,digest,legacyTimes,volatileMetadata);
                    count++;
                }
                reader.endObject(); digest.update((byte)'}'); break;
            case BEGIN_ARRAY:
                digest.update((byte)'['); reader.beginArray();
                while(reader.hasNext()) { value(reader,digest,legacyTimes,volatileMetadata); count++; }
                reader.endArray(); digest.update((byte)']'); break;
            case STRING: token(digest,'S',reader.nextString()); break;
            case NUMBER: token(digest,'N',reader.nextString()); break;
            case BOOLEAN: token(digest,'B',Boolean.toString(reader.nextBoolean())); break;
            case NULL: reader.nextNull(); digest.update((byte)'0'); break;
            default: throw new IllegalStateException("Invalid JSON token");
        }
        return count;
    }
    static Snapshot read(String path) throws Exception {
        File file=new File(path); checksum(file);
        Snapshot result=new Snapshot();
        try(JsonReader reader=new JsonReader(new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8),65536))) {
            reader.beginObject(); Set<String> names=new HashSet<>();
            while(reader.hasNext()) {
                String name=reader.nextName(); require(names.add(name),"Duplicate export field");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                if(name.equals("versions")) {
                    digest.update((byte)'{'); reader.beginObject();
                    while(reader.hasNext()) {
                        token(digest,'K',reader.nextName()); reader.beginArray();
                        List<String> versions=new ArrayList<>();
                        while(reader.hasNext()) {
                            MessageDigest version=MessageDigest.getInstance("SHA-256");
                            value(reader,version,true); versions.add(hex(version.digest())); result.versions++;
                        }
                        reader.endArray(); Collections.sort(versions);
                        digest.update((byte)'[');
                        for(String version:versions) token(digest,'V',version);
                        digest.update((byte)']');
                    }
                    reader.endObject(); digest.update((byte)'}');
                } else {
                    int count=value(reader,digest,false,name.equals("items") || name.equals("library"));
                    if(name.equals("docs")) result.docs=count;
                    if(name.equals("images")) result.images=count;
                    if(name.equals("templates")) result.templates=count;
                    if(name.equals("exportRecords")) result.exports=count;
                }
                if(!name.equals("exportedAt") && !name.equals("checksum") && !name.equals("revision")) result.fields.put(name,hex(digest.digest()));
            }
            reader.endObject(); require(reader.peek()==JsonToken.END_DOCUMENT,"Trailing export data");
        }
        require(result.fields.containsKey("docs") && result.fields.containsKey("items"),"Incomplete export");
        return result;
    }
    public static String verify(String beforePath,String afterPath) throws Exception {
        Snapshot before=read(beforePath), after=read(afterPath);
        Set<String> changed=new TreeSet<>(before.fields.keySet()); changed.addAll(after.fields.keySet()); changed.removeIf(key -> Objects.equals(before.fields.get(key),after.fields.get(key))); require(changed.isEmpty(),"Export payload fields differ: "+changed);
        return "PASS exact document content, formatting, comments, folder metadata and images preserved\n"
            +"Both canonical v2 SHA-256 checksums verified; document payloads, images and stable folder metadata identical (last-open/revision counters excluded); local version contents matched independent of list order; legacy time fields excluded (reconstructed from original file save time)\n"
            +"Original documents: "+before.docs+"; upgraded documents: "+after.docs+"; image files: "+after.images+"\n"
            +"Local versions: "+after.versions+"; templates: "+after.templates+"; download records: "+after.exports+"; no HISTORY versions\n";
    }
    public static void main(String[] args) throws Exception {
        require(args.length==2,"Expected two export paths");
        System.out.print(verify(args[0],args[1]));
    }
}
