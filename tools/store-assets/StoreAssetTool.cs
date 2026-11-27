// Play ストアの掲載用の画像・動画を仕上げる小さな道具(Windows 専用)。
//
// - flatten: PNG の透明度(アルファ)を落として 24 ビットの PNG にする。
//   Play Console はフィーチャー グラフィックにアルファ付きの PNG を受け付けないため。
//   Roborazzi が書き出す PNG は不透明でも 32 ビット(アルファ付き)なので、全部これで書き直す。
// - encode: 連番の PNG を H.264 の MP4 にする。Windows に標準で入っている Media Foundation の
//   エンコーダを使うので、ffmpeg などを別に入れなくてよい。
//
// .NET Framework 4 の csc(C# 5)でビルドできるように、新しい構文は使っていない。
// ビルドと使い方は tools/store-assets/make-store-assets.ps1 を見ること。

using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;

namespace EchoMarkStoreAssets
{
    internal static class Program
    {
        [MTAThread]
        private static int Main(string[] args)
        {
            try
            {
                if (args.Length == 3 && args[0] == "flatten")
                {
                    Flatten(args[1], args[2]);
                    return 0;
                }
                if (args.Length == 5 && args[0] == "encode")
                {
                    Encode(args[1], int.Parse(args[2]), int.Parse(args[3]), args[4]);
                    return 0;
                }
                Console.Error.WriteLine("使い方:");
                Console.Error.WriteLine("  StoreAssetTool flatten <入力.png> <出力.png>");
                Console.Error.WriteLine("  StoreAssetTool encode <連番 PNG のフォルダ> <fps> <ビットレート(bps)> <出力.mp4>");
                return 2;
            }
            catch (Exception e)
            {
                Console.Error.WriteLine(e);
                return 1;
            }
        }

        /// <summary>アルファを白ではなく元の色のまま落とす(不透明な画像なので見た目は変わらない)。</summary>
        private static void Flatten(string input, string output)
        {
            using (var source = new Bitmap(input))
            using (var flat = new Bitmap(source.Width, source.Height, PixelFormat.Format24bppRgb))
            {
                flat.SetResolution(72, 72);
                using (var g = Graphics.FromImage(flat))
                {
                    g.Clear(Color.Black);
                    g.DrawImage(source, new Rectangle(0, 0, source.Width, source.Height));
                }
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(output)));
                flat.Save(output, ImageFormat.Png);
            }
        }

        private static void Encode(string framesDir, int fps, int bitrate, string output)
        {
            var frames = Directory.GetFiles(framesDir, "*.png").OrderBy(f => f, StringComparer.Ordinal).ToArray();
            if (frames.Length == 0) throw new InvalidOperationException("PNG がありません: " + framesDir);

            int width, height;
            using (var first = new Bitmap(frames[0]))
            {
                width = first.Width;
                height = first.Height;
            }
            Console.WriteLine("{0} 枚 ({1}x{2}, {3} fps) を {4} に書き出します", frames.Length, width, height, fps, output);

            var fullOutput = Path.GetFullPath(output);
            Directory.CreateDirectory(Path.GetDirectoryName(fullOutput));
            if (File.Exists(fullOutput)) File.Delete(fullOutput);

            Check(Mf.MFStartup(Mf.MF_VERSION, 0), "MFStartup");
            try
            {
                using (var writer = new H264Writer(fullOutput, width, height, fps, bitrate))
                {
                    var stride = width * 4;
                    var pixels = new byte[stride * height];
                    for (var i = 0; i < frames.Length; i++)
                    {
                        using (var bitmap = new Bitmap(frames[i]))
                        {
                            if (bitmap.Width != width || bitmap.Height != height)
                                throw new InvalidOperationException("大きさがそろっていません: " + frames[i]);
                            var data = bitmap.LockBits(new Rectangle(0, 0, width, height), ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
                            try
                            {
                                // GDI+ の 32bppRgb はメモリ上 B,G,R,X の並びで、Media Foundation の RGB32 と同じ
                                for (var y = 0; y < height; y++)
                                    Marshal.Copy(data.Scan0 + y * data.Stride, pixels, y * stride, stride);
                            }
                            finally
                            {
                                bitmap.UnlockBits(data);
                            }
                        }
                        writer.WriteFrame(pixels, i);
                        if (i % 60 == 0) Console.WriteLine("  {0} / {1}", i, frames.Length);
                    }
                }
            }
            finally
            {
                Mf.MFShutdown();
            }
            Console.WriteLine("できました: {0} ({1:N0} バイト)", fullOutput, new FileInfo(fullOutput).Length);
        }

        internal static void Check(int hr, string what)
        {
            if (hr < 0) throw new COMException(what + " が失敗しました (0x" + hr.ToString("X8") + ")", hr);
        }
    }

    /// <summary>Media Foundation の Sink Writer で、RGB32 の画像を H.264 (High) の MP4 に書く。</summary>
    internal sealed class H264Writer : IDisposable
    {
        private readonly IMFSinkWriter writer;
        private readonly int streamIndex;
        private readonly int width;
        private readonly int height;
        private readonly long frameDuration;

        public H264Writer(string path, int width, int height, int fps, int bitrate)
        {
            this.width = width;
            this.height = height;
            frameDuration = 10000000L / fps;

            Program.Check(Mf.MFCreateSinkWriterFromURL(path, IntPtr.Zero, IntPtr.Zero, out writer), "MFCreateSinkWriterFromURL");

            IMFMediaType outType;
            Program.Check(Mf.MFCreateMediaType(out outType), "MFCreateMediaType(out)");
            Set(outType, Mf.MF_MT_MAJOR_TYPE, Mf.MFMediaType_Video);
            Set(outType, Mf.MF_MT_SUBTYPE, Mf.MFVideoFormat_H264);
            Program.Check(outType.SetUINT32(Mf.MF_MT_AVG_BITRATE, bitrate), "AVG_BITRATE");
            Program.Check(outType.SetUINT32(Mf.MF_MT_INTERLACE_MODE, Mf.MFVideoInterlace_Progressive), "INTERLACE");
            Program.Check(outType.SetUINT32(Mf.MF_MT_MPEG2_PROFILE, Mf.eAVEncH264VProfile_High), "PROFILE");
            SetPair(outType, Mf.MF_MT_FRAME_SIZE, width, height);
            SetPair(outType, Mf.MF_MT_FRAME_RATE, fps, 1);
            SetPair(outType, Mf.MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
            Program.Check(writer.AddStream(outType, out streamIndex), "AddStream");
            Marshal.ReleaseComObject(outType);

            IMFMediaType inType;
            Program.Check(Mf.MFCreateMediaType(out inType), "MFCreateMediaType(in)");
            Set(inType, Mf.MF_MT_MAJOR_TYPE, Mf.MFMediaType_Video);
            Set(inType, Mf.MF_MT_SUBTYPE, Mf.MFVideoFormat_RGB32);
            Program.Check(inType.SetUINT32(Mf.MF_MT_INTERLACE_MODE, Mf.MFVideoInterlace_Progressive), "INTERLACE(in)");
            // 正の stride = 上の行から並んだ画像(GDI+ と同じ向き)。指定しないと RGB は下から並んだものと見なされ、上下が逆になる
            Program.Check(inType.SetUINT32(Mf.MF_MT_DEFAULT_STRIDE, width * 4), "DEFAULT_STRIDE");
            SetPair(inType, Mf.MF_MT_FRAME_SIZE, width, height);
            SetPair(inType, Mf.MF_MT_FRAME_RATE, fps, 1);
            SetPair(inType, Mf.MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
            Program.Check(writer.SetInputMediaType(streamIndex, inType, IntPtr.Zero), "SetInputMediaType");
            Marshal.ReleaseComObject(inType);

            Program.Check(writer.BeginWriting(), "BeginWriting");
        }

        public void WriteFrame(byte[] pixels, int index)
        {
            var length = width * 4 * height;
            IMFMediaBuffer buffer;
            Program.Check(Mf.MFCreateMemoryBuffer(length, out buffer), "MFCreateMemoryBuffer");
            IMFSample sample = null;
            try
            {
                IntPtr ptr;
                int max, current;
                Program.Check(buffer.Lock(out ptr, out max, out current), "Lock");
                Marshal.Copy(pixels, 0, ptr, length);
                Program.Check(buffer.Unlock(), "Unlock");
                Program.Check(buffer.SetCurrentLength(length), "SetCurrentLength");

                Program.Check(Mf.MFCreateSample(out sample), "MFCreateSample");
                Program.Check(sample.AddBuffer(buffer), "AddBuffer");
                Program.Check(sample.SetSampleTime(index * frameDuration), "SetSampleTime");
                Program.Check(sample.SetSampleDuration(frameDuration), "SetSampleDuration");
                Program.Check(writer.WriteSample(streamIndex, sample), "WriteSample");
            }
            finally
            {
                if (sample != null) Marshal.ReleaseComObject(sample);
                Marshal.ReleaseComObject(buffer);
            }
        }

        public void Dispose()
        {
            Program.Check(writer.DoFinalize(), "Finalize");
            Marshal.ReleaseComObject(writer);
        }

        private static void Set(IMFMediaType type, Guid key, Guid value)
        {
            Program.Check(type.SetGUID(key, value), "SetGUID");
        }

        // MFSetAttributeSize / MFSetAttributeRatio と同じ(上位 32 ビットと下位 32 ビットに2つの値を詰める)
        private static void SetPair(IMFMediaType type, Guid key, int high, int low)
        {
            Program.Check(type.SetUINT64(key, ((long)high << 32) | (uint)low), "SetUINT64");
        }
    }

    internal static class Mf
    {
        public const int MF_VERSION = 0x00020070;
        public const int MFVideoInterlace_Progressive = 2;
        public const int eAVEncH264VProfile_High = 100;

        public static readonly Guid MF_MT_MAJOR_TYPE = new Guid("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");
        public static readonly Guid MF_MT_SUBTYPE = new Guid("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
        public static readonly Guid MF_MT_AVG_BITRATE = new Guid("20332624-fb0d-4d9e-bd0d-cbf6786c102e");
        public static readonly Guid MF_MT_INTERLACE_MODE = new Guid("e2724bb8-e676-4806-b4b2-a8d6efb44ccd");
        public static readonly Guid MF_MT_FRAME_SIZE = new Guid("1652c33d-d6b2-4012-b834-72030849a37d");
        public static readonly Guid MF_MT_FRAME_RATE = new Guid("c459a2e8-3d2c-4e44-b132-fee5156c7bb0");
        public static readonly Guid MF_MT_PIXEL_ASPECT_RATIO = new Guid("c6376a1e-8d0a-4027-be45-6d9a0ad39bb6");
        public static readonly Guid MF_MT_MPEG2_PROFILE = new Guid("ad76a80b-2d5c-4e0b-b375-64e520137036");
        public static readonly Guid MF_MT_DEFAULT_STRIDE = new Guid("644b4e48-1e02-4516-b0eb-c01ca9d49ac6");
        public static readonly Guid MFMediaType_Video = new Guid("73646976-0000-0010-8000-00AA00389B71");
        public static readonly Guid MFVideoFormat_H264 = new Guid("34363248-0000-0010-8000-00AA00389B71");
        public static readonly Guid MFVideoFormat_RGB32 = new Guid("00000016-0000-0010-8000-00AA00389B71");

        [DllImport("mfplat.dll")]
        public static extern int MFStartup(int version, int flags);

        [DllImport("mfplat.dll")]
        public static extern int MFShutdown();

        [DllImport("mfplat.dll")]
        public static extern int MFCreateMediaType(out IMFMediaType type);

        [DllImport("mfplat.dll")]
        public static extern int MFCreateSample(out IMFSample sample);

        [DllImport("mfplat.dll")]
        public static extern int MFCreateMemoryBuffer(int maxLength, out IMFMediaBuffer buffer);

        [DllImport("mfreadwrite.dll")]
        public static extern int MFCreateSinkWriterFromURL(
            [MarshalAs(UnmanagedType.LPWStr)] string url, IntPtr byteStream, IntPtr attributes, out IMFSinkWriter writer);
    }

    // COM の vtable は宣言の順で決まる。使わない枠は名前だけの仮のメソッドで埋めている(呼ばないので引数は問わない)。
    // .NET の COM 相互運用はインターフェースの継承を vtable に反映しないため、IMFAttributes の 30 個の枠を毎回並べる。

    [ComImport, Guid("44ae0fa8-ea31-4109-8d2e-4cae4997c555"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMFMediaType
    {
        [PreserveSig] int A00(); [PreserveSig] int A01(); [PreserveSig] int A02(); [PreserveSig] int A03();
        [PreserveSig] int A04(); [PreserveSig] int A05(); [PreserveSig] int A06(); [PreserveSig] int A07();
        [PreserveSig] int A08(); [PreserveSig] int A09(); [PreserveSig] int A10(); [PreserveSig] int A11();
        [PreserveSig] int A12(); [PreserveSig] int A13(); [PreserveSig] int A14(); [PreserveSig] int A15();
        [PreserveSig] int A16(); [PreserveSig] int A17();
        [PreserveSig] int SetUINT32([In, MarshalAs(UnmanagedType.LPStruct)] Guid key, int value);
        [PreserveSig] int SetUINT64([In, MarshalAs(UnmanagedType.LPStruct)] Guid key, long value);
        [PreserveSig] int A20();
        [PreserveSig] int SetGUID([In, MarshalAs(UnmanagedType.LPStruct)] Guid key, [In, MarshalAs(UnmanagedType.LPStruct)] Guid value);
        [PreserveSig] int A22(); [PreserveSig] int A23(); [PreserveSig] int A24(); [PreserveSig] int A25();
        [PreserveSig] int A26(); [PreserveSig] int A27(); [PreserveSig] int A28(); [PreserveSig] int A29();
        // IMFMediaType 自身のメソッド(GetMajorType など)は使わない
    }

    [ComImport, Guid("c40a00f2-b93a-4d80-ae8c-5a1c634f58e4"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMFSample
    {
        [PreserveSig] int A00(); [PreserveSig] int A01(); [PreserveSig] int A02(); [PreserveSig] int A03();
        [PreserveSig] int A04(); [PreserveSig] int A05(); [PreserveSig] int A06(); [PreserveSig] int A07();
        [PreserveSig] int A08(); [PreserveSig] int A09(); [PreserveSig] int A10(); [PreserveSig] int A11();
        [PreserveSig] int A12(); [PreserveSig] int A13(); [PreserveSig] int A14(); [PreserveSig] int A15();
        [PreserveSig] int A16(); [PreserveSig] int A17(); [PreserveSig] int A18(); [PreserveSig] int A19();
        [PreserveSig] int A20(); [PreserveSig] int A21(); [PreserveSig] int A22(); [PreserveSig] int A23();
        [PreserveSig] int A24(); [PreserveSig] int A25(); [PreserveSig] int A26(); [PreserveSig] int A27();
        [PreserveSig] int A28(); [PreserveSig] int A29();
        [PreserveSig] int GetSampleFlags(out int flags);
        [PreserveSig] int SetSampleFlags(int flags);
        [PreserveSig] int GetSampleTime(out long time);
        [PreserveSig] int SetSampleTime(long time);
        [PreserveSig] int GetSampleDuration(out long duration);
        [PreserveSig] int SetSampleDuration(long duration);
        [PreserveSig] int GetBufferCount(out int count);
        [PreserveSig] int GetBufferByIndex(int index, out IMFMediaBuffer buffer);
        [PreserveSig] int ConvertToContiguousBuffer(out IMFMediaBuffer buffer);
        [PreserveSig] int AddBuffer(IMFMediaBuffer buffer);
    }

    [ComImport, Guid("045FA593-8799-42b8-BC8D-8968C6453507"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMFMediaBuffer
    {
        [PreserveSig] int Lock(out IntPtr buffer, out int maxLength, out int currentLength);
        [PreserveSig] int Unlock();
        [PreserveSig] int GetCurrentLength(out int length);
        [PreserveSig] int SetCurrentLength(int length);
        [PreserveSig] int GetMaxLength(out int length);
    }

    [ComImport, Guid("3137f1cd-fe5e-4805-a5d8-fb477448cb3d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMFSinkWriter
    {
        [PreserveSig] int AddStream(IMFMediaType targetType, out int streamIndex);
        [PreserveSig] int SetInputMediaType(int streamIndex, IMFMediaType inputType, IntPtr encodingParameters);
        [PreserveSig] int BeginWriting();
        [PreserveSig] int WriteSample(int streamIndex, IMFSample sample);
        [PreserveSig] int SendStreamTick(int streamIndex, long timestamp);
        [PreserveSig] int PlaceMarker(int streamIndex, IntPtr context);
        [PreserveSig] int NotifyEndOfSegment(int streamIndex);
        [PreserveSig] int Flush(int streamIndex);
        // IMFSinkWriter::Finalize。C# の Finalize と名前がぶつかるので変えている
        [PreserveSig] int DoFinalize();
    }
}
