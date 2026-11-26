using System.IO.Ports;

namespace CamaroDiagConnectors.Windows;

// Explicit, exclusively owned serial channel; authorization is live.
internal sealed class J1979SerialPortLink(SerialPort port,Func<bool> current):J1979UsbCapture.ILink
{
    private bool closed;
    private void Current(){
        if(closed||!port.IsOpen||!current())throw new IOException("USB session closed or revoked");
    }
    public int Read(byte[] data,int offset,int size,int timeoutMs){
        long start=Environment.TickCount64;
        while(true){
            Current();long elapsed=Environment.TickCount64-start;
            if(elapsed<0||elapsed>=timeoutMs)throw new TimeoutException("USB read deadline exceeded");
            port.ReadTimeout=(int)Math.Min(100,timeoutMs-elapsed);
            try{int n=port.Read(data,offset,size);if(n>0)return n;}
            catch(TimeoutException){ /* wait within original deadline */ }
        }
    }
    public void Write(byte[] data,int timeoutMs){
        Current();port.WriteTimeout=Math.Min(timeoutMs,1000);
        port.Write(data,0,data.Length);Current();
    }
    public void CheckNoBufferedInput(){
        Current();if(port.BytesToRead!=0)throw new IOException("Unexpected buffered CU/1 bytes");
    }
    public void Dispose(){if(!closed){closed=true;port.Close();}}
}
