using System.Buffers.Binary;
using System.Security.Cryptography;

namespace CamaroDiagConnectors.Windows;

internal static class J1979UsbAuth
{
    internal const int HelloSize = 49, ChallengeSize = 68, ProofSize = 36;
    internal static bool PhysicalPair(uint tx,uint rx,bool extended)
    {
        if(!extended)return tx is >=0x7e0 and <=0x7e7 && rx==tx+8;
        if(tx>0x1fffffff||(tx&0x1fff00ff)!=0x18da00f1)return false;
        uint source=(tx>>8)&255;
        return source<0xf0&&rx==(0x18daf100|source);
    }
    private static bool Nonzero(ReadOnlySpan<byte> data)
    {
        byte sum=0;foreach(byte b in data)sum|=b;return sum!=0;
    }
    internal static byte[] Hello(uint tx,uint rx,bool extended,int budgetMs) =>
        Hello(tx,rx,extended,budgetMs,RandomNumberGenerator.GetBytes(32));
    internal static byte[] Hello(uint tx,uint rx,bool extended,int budgetMs,ReadOnlySpan<byte> nonce)
    {
        if(!PhysicalPair(tx,rx,extended)||budgetMs is <1 or >600000||nonce.Length!=32||!Nonzero(nonce))
            throw new ArgumentException("Invalid CU/1 profile or nonce");
        byte[] request=new byte[HelloSize];
        "CU"u8.CopyTo(request);request[2]=1;request[3]=1;
        BinaryPrimitives.WriteUInt32LittleEndian(request.AsSpan(4),tx);
        BinaryPrimitives.WriteUInt32LittleEndian(request.AsSpan(8),rx);
        request[12]=extended?(byte)1:(byte)0;
        BinaryPrimitives.WriteInt32LittleEndian(request.AsSpan(13),budgetMs);
        nonce.CopyTo(request.AsSpan(17));return request;
    }
    internal static byte[] Proof(ReadOnlySpan<byte> request,ReadOnlySpan<byte> challenge,ReadOnlySpan<byte> key)
    {
        if(request.Length!=HelloSize||challenge.Length!=ChallengeSize||key.Length!=32||
           !request[..4].SequenceEqual("CU\x01\x01"u8)||!challenge[..4].SequenceEqual("CU\x01\x02"u8)||
           !Nonzero(request[17..])||!Nonzero(challenge[4..36]))
            throw new IOException("Invalid CU/1 transcript");
        uint tx=BinaryPrimitives.ReadUInt32LittleEndian(request[4..]),rx=BinaryPrimitives.ReadUInt32LittleEndian(request[8..]);
        int budget=BinaryPrimitives.ReadInt32LittleEndian(request[13..]);
        if(request[12]>1||!PhysicalPair(tx,rx,request[12]==1)||budget is <1 or >600000)
            throw new IOException("Invalid CU/1 profile");
        Span<byte> transcript=stackalloc byte[78];
        request[4..].CopyTo(transcript[1..]);challenge[4..36].CopyTo(transcript[46..]);
        try{
            transcript[0]=(byte)'S';
            byte[] expected=HMACSHA256.HashData(key,transcript);
            if(!CryptographicOperations.FixedTimeEquals(expected,challenge[36..]))
                throw new IOException("Device authentication failed");
            transcript[0]=(byte)'C';
            byte[] result=new byte[ProofSize];"CU\x01\x03"u8.CopyTo(result);
            HMACSHA256.HashData(key,transcript).CopyTo(result.AsSpan(4));return result;
        }finally{CryptographicOperations.ZeroMemory(transcript);}
    }
}
