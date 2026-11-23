package com.subtitletool.standalone;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Validate exact WAV header and payload before any billable HTTP call. */
public final class AudioIntegrity {
    private AudioIntegrity() { }
    private static long u32(RandomAccessFile f)throws IOException {
        return ((long)f.readUnsignedByte()) |
               ((long)f.readUnsignedByte()<<8) |
               ((long)f.readUnsignedByte()<<16) |
               ((long)f.readUnsignedByte()<<24);
    }
    private static int u16(RandomAccessFile f)throws IOException {
        return f.readUnsignedByte() | (f.readUnsignedByte()<<8);
    }
    private static String fourcc(RandomAccessFile f)throws IOException {
        byte[] b=new byte[4];f.readFully(b);
        return new String(b,java.nio.charset.StandardCharsets.US_ASCII);
    }
    public static void validatePcmWav(File file)throws IOException {
        if(file==null || !file.isFile())throw new IOException("音频输出文件不存在");
        long length=file.length();
        if(length<44+3200)throw new IOException("音频太短或为空，已取消 API 请求");
        if(length>2_000_000_000L)throw new IOException("音频过大，请尝试较短的素材");
        try(RandomAccessFile f=new RandomAccessFile(file,"r")) {
            if(!"RIFF".equals(fourcc(f)))throw new IOException("无效音频：缺少 RIFF 文件头");
            long riffSize=u32(f);
            if(!"WAVE".equals(fourcc(f)))throw new IOException("无效音频：缺少 WAVE 标记");
            if(!"fmt ".equals(fourcc(f)))throw new IOException("无效音频：缺少 PCM 格式描述");
            if(u32(f)!=16)throw new IOException("无效音频：WAV 格式字段异常");
            int format=u16(f), channels=u16(f);
            long rate=u32(f),byteRate=u32(f);
            int align=u16(f),bits=u16(f);
            if(!"data".equals(fourcc(f)))throw new IOException("无效音频：缺少 data 段");
            long dataSize=u32(f);
            if(format!=1 || channels!=1 || rate!=16000 || byteRate!=32000 || align!=2 || bits!=16)
                throw new IOException("无效音频：不是 16kHz 单声道 16-bit PCM");
            if(riffSize!=length-8 || dataSize!=length-44 || (dataSize%2)!=0)
                throw new IOException("无效音频：WAV 长度与文件头不一致");
        }
    }
}
