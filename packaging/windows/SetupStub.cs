using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Text;
using System.Windows.Forms;

// Extracts the app image appended to this exe and runs YarmiplayTV.exe --setup with the same arguments.
// The bytes at the end are: zip, int64 little-endian zip length, ASCII "YPTVZIP1" (see writeSetupFooter).
class SetupStub {
    const string Magic = "YPTVZIP1";

    [STAThread]
    static int Main() {
        try {
            return Run();
        } catch (Exception ex) {
            MessageBox.Show(ex.Message, "YarmiplayTV", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    static int Run() {
        string self = Process.GetCurrentProcess().MainModule.FileName;
        string tempRoot = Path.Combine(Path.GetTempPath(), "YarmiplayTV-setup-" + Process.GetCurrentProcess().Id);
        Directory.CreateDirectory(tempRoot);
        try {
            string zipPath = Path.Combine(tempRoot, "image.zip");
            CopyPayload(self, zipPath);
            ZipFile.ExtractToDirectory(zipPath, tempRoot);
            File.Delete(zipPath);
            string exe = FindLauncher(tempRoot);
            ProcessStartInfo start = new ProcessStartInfo();
            start.FileName = exe;
            start.WorkingDirectory = Path.GetDirectoryName(exe);
            start.UseShellExecute = false;
            start.Arguments = ChildArguments();
            Process child = Process.Start(start);
            if (child == null) throw new InvalidOperationException("Couldn't start the installer.");
            child.WaitForExit();
            return child.ExitCode;
        } finally {
            try { Directory.Delete(tempRoot, true); } catch (Exception) { }
        }
    }

    static void CopyPayload(string self, string zipPath) {
        using (FileStream input = new FileStream(self, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) {
            if (input.Length < 16) throw new InvalidOperationException("This setup file is incomplete.");
            input.Seek(-16, SeekOrigin.End);
            byte[] tail = new byte[16];
            if (input.Read(tail, 0, 16) != 16) throw new InvalidOperationException("This setup file is incomplete.");
            if (Encoding.ASCII.GetString(tail, 8, 8) != Magic) throw new InvalidOperationException("This setup file is incomplete.");
            long zipLen = BitConverter.ToInt64(tail, 0);
            if (zipLen <= 0 || zipLen > input.Length - 16) throw new InvalidOperationException("This setup file is incomplete.");
            input.Seek(-(16 + zipLen), SeekOrigin.End);
            using (FileStream output = File.Create(zipPath)) {
                byte[] buf = new byte[1024 * 1024];
                long left = zipLen;
                while (left > 0) {
                    int n = input.Read(buf, 0, (int)Math.Min((long)buf.Length, left));
                    if (n <= 0) throw new InvalidOperationException("This setup file is incomplete.");
                    output.Write(buf, 0, n);
                    left -= n;
                }
            }
        }
    }

    static string FindLauncher(string root) {
        string[] hits = Directory.GetFiles(root, "YarmiplayTV.exe", SearchOption.AllDirectories);
        if (hits.Length == 0) throw new InvalidOperationException("The setup package has no YarmiplayTV.exe.");
        foreach (string hit in hits) {
            string dir = Path.GetDirectoryName(hit);
            if (Directory.Exists(Path.Combine(dir, "runtime"))) return hit;
        }
        return hits[0];
    }

    static string ChildArguments() {
        string[] all = Environment.GetCommandLineArgs();
        StringBuilder args = new StringBuilder();
        args.Append(Quote("--setup"));
        for (int i = 1; i < all.Length; i++) {
            if (string.Equals(all[i], "--setup", StringComparison.OrdinalIgnoreCase)) continue;
            args.Append(' ');
            args.Append(Quote(all[i]));
        }
        return args.ToString();
    }

    static string Quote(string arg) {
        if (arg.Length > 0 && arg.IndexOf(' ') < 0 && arg.IndexOf('\t') < 0 && arg.IndexOf('"') < 0) return arg;
        StringBuilder quoted = new StringBuilder();
        quoted.Append('"');
        int slashes = 0;
        foreach (char c in arg) {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') quoted.Append('\\', slashes * 2 + 1);
            else quoted.Append('\\', slashes);
            slashes = 0;
            quoted.Append(c);
        }
        quoted.Append('\\', slashes * 2);
        quoted.Append('"');
        return quoted.ToString();
    }
}
