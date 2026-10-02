<#
.SYNOPSIS
  Saves a screenshot of a top-level window (by title) without bringing it to the front, so it works while
  other windows cover it and never disturbs what's on screen. Used to check the desktop app by eye.

.EXAMPLE
  ./scripts/window-shot.ps1 -Title SyncplayTV -Out build/desktop-shots/home.png
#>
param(
    [Parameter(Mandatory)] [string]$Title,
    [Parameter(Mandatory)] [string]$Out
)

Add-Type -AssemblyName System.Drawing
if (-not ("WindowShot" -as [type])) {
    Add-Type -ReferencedAssemblies System.Drawing @"
using System; using System.Drawing; using System.Runtime.InteropServices;
public static class WindowShot {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern IntPtr FindWindow(string cls, string title);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    public static Bitmap Capture(string title) {
        IntPtr hwnd = FindWindow(null, title);
        if (hwnd == IntPtr.Zero) return null;
        RECT r; GetWindowRect(hwnd, out r);
        var bmp = new Bitmap(r.Right - r.Left, r.Bottom - r.Top);
        using (var g = Graphics.FromImage(bmp)) {
            IntPtr hdc = g.GetHdc();
            // PW_RENDERFULLCONTENT: includes GPU-rendered content (Skia/OpenGL), even when covered.
            PrintWindow(hwnd, hdc, 2);
            g.ReleaseHdc(hdc);
        }
        return bmp;
    }
}
"@
}
[void][WindowShot]::SetProcessDPIAware()
$bmp = [WindowShot]::Capture($Title)
if (-not $bmp) { throw "No window titled '$Title'." }
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
$bmp.Save((Join-Path (Get-Location) $Out), [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "Saved $Out ($($bmp.Width)x$($bmp.Height))"
$bmp.Dispose()
