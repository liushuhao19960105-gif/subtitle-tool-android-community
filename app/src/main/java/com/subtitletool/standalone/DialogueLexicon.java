package com.subtitletool.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Curated context hints, never direct string substitutions.
 * No audio, keys, transcripts or API calls are written to this lexicon.
 */
final class DialogueLexicon {
    private static final int MAX_ENTRIES=400,MAX_HINTS=10,MAX_HINT_CHARS=1800;
    private static final String HEADER="id\ttrigger\tcategory\tcontext_zh\ttranslation_zh\tavoid_zh";
    static final class Entry {
        final String id,term,category,context,translation,avoid;
        final Pattern match;
        Entry(String[] fields) {
            id=fields[0];term=fields[1];category=fields[2];
            context=fields[3];translation=fields[4];avoid=fields[5];
            // Avoid matching 'come' inside 'become'; preserve whole multiword phrases.
            match=Pattern.compile("(?<![\\p{L}\\p{N}])"+Pattern.quote(term)+
                    "(?![\\p{L}\\p{N}])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
        }
    }
    private final List<Entry> entries;
    private DialogueLexicon(List<Entry> entries){this.entries=Collections.unmodifiableList(entries);}
    int size(){return entries.size();}
    static DialogueLexicon load(Reader source)throws IOException {
        BufferedReader reader=source instanceof BufferedReader?(BufferedReader)source:new BufferedReader(source);
        String first=reader.readLine();
        if(!HEADER.equals(first))throw new IOException("对白词库表头不完整");
        List<Entry> items=new ArrayList<>();
        Set<String> ids=new HashSet<>();
        String line;
        while((line=reader.readLine())!=null) {
            if(line.isEmpty())continue;
            if(line.length()>600)throw new IOException("对白词库单行超长");
            if(items.size()>=MAX_ENTRIES)throw new IOException("对白词库条目超出限制");
            String[] data=line.split("\t",-1);
            if(data.length!=6)throw new IOException("对白词库 TSV 字段数量错误");
            for(String value:data)if(value.trim().isEmpty() || value.length()>240)
                throw new IOException("对白词库字段为空或过长");
            if(!data[0].matches("[0-9]{3,4}") || !ids.add(data[0]) ||
               data[1].length()>60 || !data[1].matches("[A-Za-z0-9 \\-]+"))
                throw new IOException("对白词库编号或匹配短语无效");
            items.add(new Entry(data));
        }
        if(items.size()<40)throw new IOException("对白词库有效条目不足");
        // Prefer the most specific phrase, e.g. 'come here' over bare 'come'.
        items.sort(Comparator.comparingInt((Entry e)->e.term.length()).reversed());
        return new DialogueLexicon(items);
    }
    /** Append only relevant, bounded evidence to each batch; don't rewrite cues. */
    JSONArray hints(List<SubtitleData.Cue> cues,int from,int to)throws Exception {
        JSONArray out=new JSONArray();
        if(cues==null || from<0 || to>cues.size() || from>=to)return out;
        StringBuilder target=new StringBuilder();
        for(int i=from;i<to;i++){
            String en=cues.get(i).en;
            if(en!=null)target.append(en).append(' ');
        }
        String english=target.toString();
        int characters=0;
        for(Entry entry:entries){
            if(out.length()>=MAX_HINTS)break;
            if(!entry.match.matcher(english).find())continue;
            int size=entry.term.length()+entry.category.length()+entry.context.length()+
                     entry.translation.length()+entry.avoid.length();
            if(characters+size>MAX_HINT_CHARS)continue;
            characters+=size;
            JSONObject hint=new JSONObject()
                    .put("en",entry.term)
                    .put("type",entry.category)
                    .put("when",entry.context)
                    .put("candidate",entry.translation)
                    .put("do_not_assume",entry.avoid);
            out.put(hint);
        }
        return out;
    }
}
