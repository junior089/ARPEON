// Compiled only by test-installer-shell.ps1. Never embedded in the distributed setup.
using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation.Peers;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace Arpeon.Setup
{
    internal static class InstallerTests
    {
        private static string output;
        private static int assertions;
        private static void Assert(bool condition, string message)
        {
            if (!condition) throw new Exception(message);
            assertions++; File.AppendAllText(Path.Combine(output, "assertions.txt"), "PASS " + message + Environment.NewLine);
        }
        private static void Reject(Action action, string message)
        {
            bool rejected = false;
            try { action(); } catch (IOException) { rejected = true; } catch (UnauthorizedAccessException) { rejected = true; }
            Assert(rejected, message);
        }
        private static object Call(object target, string method, params object[] arguments)
        {
            return target.GetType().GetMethod(method, BindingFlags.NonPublic | BindingFlags.Instance).Invoke(target, arguments);
        }
        private static T Find<T>(Window window, string name) where T : class { return (T)window.FindName(name); }
        private static int Stage(InstallerWindow ui)
        {
            return (int)typeof(InstallerWindow).GetField("stage", BindingFlags.NonPublic | BindingFlags.Instance).GetValue(ui);
        }
        private static async Task Wait(Func<bool> condition, int seconds)
        {
            var timer = Stopwatch.StartNew();
            while (!condition()) { if (timer.Elapsed.TotalSeconds > seconds) throw new TimeoutException("Installer state timed out."); await Task.Delay(100); }
        }
        private static void Capture(Window window, string name, double scale)
        {
            window.UpdateLayout();
            var visual = (FrameworkElement)window.Content;
            var bitmap = new RenderTargetBitmap((int)(window.ActualWidth * scale), (int)(window.ActualHeight * scale), 96 * scale, 96 * scale, PixelFormats.Pbgra32);
            bitmap.Render(visual);
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
            using (var stream = File.Create(Path.Combine(output, name + ".png"))) encoder.Save(stream);
        }
        private static void ProgressTests()
        {
            var p = new NativeProgress();
            p.Message(0, 100, 0, 0); p.Message(2, 30, 0, 0); Assert(p.Percent == 30, "MSI absolute ticks map to current-phase percentage");
            p.Message(3, 100, 0, 0); Assert(p.Percent == 15, "MSI additions update the denominator");
            p.Message(1, 10, 1, 0); p.ActionData(); Assert(p.Position == 40, "ActionData advances only when ActionInfo enables it");
            p.ResetAction(); p.ActionData(); Assert(p.Position == 40, "ActionStart resets ActionData increments");
            p.Message(0, 100, 1, 0); p.Message(2, 25, 0, 0); Assert(p.Position == 75 && p.Backwards, "Rollback supports reverse progress");
            p.Message(2, 1000, 0, 0); Assert(p.Percent == 0, "Progress clamps below zero");
            p.Message(0, 0, 0, 1); Assert(p.Percent == 0 && p.Estimating, "Unknown and estimated totals remain indeterminate");
        }
        private static async Task CaptureMotion(Window window)
        {
            var canvas = Find<Canvas>(window, "BrandScene");
            var encoder = new GifBitmapEncoder();
            int firstHash = 0; bool changed = false;
            for (int i = 0; i < 36; i++) {
                await Task.Delay(100);
                var bitmap = new RenderTargetBitmap(300, 300, 96, 96, PixelFormats.Pbgra32); bitmap.Render(canvas);
                byte[] pixels = new byte[300 * 300 * 4]; bitmap.CopyPixels(pixels, 300 * 4, 0);
                int hash = 17; for (int p = 0; p < pixels.Length; p += 37) hash = unchecked(hash * 31 + pixels[p]);
                if (i == 0) firstHash = hash; else if (hash != firstHash) changed = true;
                var metadata = new BitmapMetadata("gif"); metadata.SetQuery("/grctlext/Delay", (ushort)10);
                if (i == 0) { metadata.SetQuery("/appext/application", System.Text.Encoding.ASCII.GetBytes("NETSCAPE2.0")); metadata.SetQuery("/appext/data", new byte[] { 3, 1, 0, 0, 0 }); }
                encoder.Frames.Add(BitmapFrame.Create(bitmap, null, metadata, null));
            }
            using (var stream = File.Create(Path.Combine(output, "brand-motion.gif"))) encoder.Save(stream);
            Assert(!SystemParameters.ClientAreaAnimation || changed, "Musical wave geometry animates across real WPF frames");
        }
        [STAThread]
        public static int Main(string[] args)
        {
            output = Path.GetFullPath(args[0]); Directory.CreateDirectory(output);
            bool lifecycle = args.Length > 1 && args[1] == "lifecycle";
            ProgressTests();
            using (var engine = new InstallerEngine()) {
                var app = new Application(); var ui = new InstallerWindow(engine); var window = ui.Window;
                window.ShowActivated = false; window.ShowInTaskbar = false; window.WindowStartupLocation = WindowStartupLocation.Manual; window.Left = -20000; window.Top = -20000;
                app.DispatcherUnhandledException += delegate(object s, System.Windows.Threading.DispatcherUnhandledExceptionEventArgs e) { File.WriteAllText(Path.Combine(output, "failure.txt"), e.Exception.ToString()); app.Shutdown(1); e.Handled = true; };
                window.Loaded += async delegate {
                    try {
                        await Task.Delay(200); // Test-only settling for the initial WPF render.
                        Capture(window, "welcome", 1);
                        await CaptureMotion(window);
                        var next = Find<Button>(window, "Next");
                        next.Focus(); Assert(Keyboard.FocusedElement == next, "Primary action accepts keyboard focus");
                        Assert(new ButtonAutomationPeer(next).GetName().Length > 0, "Primary action has an accessible name");
                        Assert(engine.CheckOperatingSystem().Length > 0 && engine.CheckArchitecture().Length > 0, "OS and architecture checks read this machine");
                        Assert(engine.CheckDependencies().Length > 0, "Bundle contains Java, CEF and Audiveris");
                        string initial = engine.Target;
                        engine.Target = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ARPEON", "projects");
                        Reject(engine.ValidateTarget, "Application directory cannot overlap personal data");
                        engine.Target = "C:\\"; Reject(engine.ValidateTarget, "Volume root cannot be the installation directory");
                        engine.Target = initial;
                        long actualSize = engine.Info.InstalledSize;
                        engine.Info.InstalledSize = engine.AvailableSpace(engine.Target);
                        Reject(delegate { engine.CheckSpace(); }, "Insufficient disk capacity blocks installation using actual free space");
                        engine.Info.InstalledSize = actualSize;
                        if (lifecycle) {
                            Assert(engine.Info.RegistryRoot.Contains("InstallerTest"), "Lifecycle execution requires isolated MSI identity");
                            if (engine.Installed) {
                                Assert(InstallerEngine.Within(engine.Target, Path.Combine(Environment.CurrentDirectory, "apps", "desktop", "build")), "Interrupted test cleanup is confined to a workspace test installation");
                                engine.Prepare();
                                uint cleanup = await Task.Run(delegate { return engine.Install(true); });
                                Assert(cleanup == 0 || cleanup == 3010, "Previous isolated test installation cleaned up through MSI");
                                engine.Dispose(); engine.DetectPrevious();
                                engine.Target = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), engine.Info.InstallFolder);
                            }
                            Assert(!engine.Installed && engine.ExistingVersion.Length == 0, "Lifecycle starts without an existing test installation");
                            string permissionTarget = Path.Combine(output, "permission-test"); Directory.CreateDirectory(permissionTarget);
                            var originalAcl = Directory.GetAccessControl(permissionTarget);
                            try {
                                var deniedAcl = Directory.GetAccessControl(permissionTarget);
                                deniedAcl.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User, FileSystemRights.CreateFiles, AccessControlType.Deny));
                                Directory.SetAccessControl(permissionTarget, deniedAcl); engine.Target = permissionTarget;
                                Reject(delegate { engine.CheckPermissions(); }, "Insufficient write permission blocks installation without requesting elevation");
                            } finally { Directory.SetAccessControl(permissionTarget, originalAcl); engine.Target = initial; }
                        }
                        Call(ui, "RunChecks"); await Wait(delegate { return Stage(ui) == 4 || next.IsEnabled; }, 60);
                        Assert(Stage(ui) == 4, "Six real checks lead to customization");
                        Capture(window, "options", 1);
                        var optionPanel = Find<StackPanel>(window, "Content"); var pathBox = (TextBox)optionPanel.Children[1];
                        var capacityLabel = optionPanel.Children.OfType<TextBlock>().First(t => t.Text.Contains("necessários"));
                        string chosenPath = pathBox.Text; pathBox.Text = "Z:\\missing-drive\\ARPEON";
                        Assert(capacityLabel.Text.Contains("Não foi possível"), "Capacity reacts to an unavailable destination instead of retaining stale data");
                        pathBox.Text = chosenPath;
                        Assert(capacityLabel.Text.Contains("disponíveis"), "Capacity recovers when the destination becomes valid");
                        Call(ui, "Notice", "Verificação de acessibilidade", "Aviso do harness de teste.");
                        await Task.Delay(100);
                        Assert(Find<TextBlock>(window, "NoticeTitle").IsKeyboardFocused, "Inline notices receive keyboard focus");
                        Assert(System.Windows.Automation.AutomationProperties.GetLiveSetting(Find<TextBlock>(window, "NoticeTitle")) == System.Windows.Automation.AutomationLiveSetting.Assertive, "Errors and confirmations have an assertive accessible live region");
                        Call(ui, "HideNotice");
                        window.Width = 780; window.Height = 550; window.UpdateLayout(); Capture(window, "compact", 1.5);
                        Assert(window.ActualWidth <= 780 && window.ActualHeight <= 550, "Small-window layout stays within the available size");
                        window.Width = 960; window.Height = 660;
                        if (lifecycle) {
                            // Keep every test-owned file inside the workspace, including a Unicode target.
                            var target = (TextBox)((StackPanel)Find<StackPanel>(window, "Content")).Children[1];
                            target.Text = Path.Combine(output, "instalação Arpeon");
                            next.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                            await Wait(delegate { return Stage(ui) == 6 || next.IsEnabled; }, 180);
                            Assert(Stage(ui) == 6, "Real MSI execution reaches the custom progress screen"); Capture(window, "installing", 1);
                            await Wait(delegate { return Stage(ui) == 8 || next.IsEnabled; }, 1200);
                            Assert(Stage(ui) == 8 && engine.Installed, "Success waits for native commit and all post-install checks");
                            Capture(window, "complete", 1);
                            Assert(engine.Committed, "Windows Installer committed the transaction");
                            string previousVersion = engine.ExistingVersion;
                            engine.ExistingVersion = engine.Info.Version; Assert(!engine.IsUpdate, "Same version remains a repair operation");
                            engine.ExistingVersion = "0.1.1"; Assert(engine.IsUpdate, "An earlier version is classified as an update"); engine.ExistingVersion = previousVersion;
                            if (engine.Info.HasMaintenance) {
                                Assert(File.Exists(Path.Combine(engine.CacheDirectory, "ARPEON.msi")), "Offline repair payload is retained in the dedicated maintenance cache");
                                string maintenance = Path.Combine(engine.CacheDirectory, "ARPEON-Maintenance.exe");
                                Assert(File.Exists(maintenance) && new FileInfo(maintenance).Length < 1024 * 1024, "Installed maintenance is a small independent native executable");
                                using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(engine.UninstallRegistryKey))
                                    Assert(key != null && Convert.ToString(key.GetValue("UninstallString")).Contains("--uninstall"), "Windows uninstall entry opens the custom removal experience");
                                string installedCode = engine.Info.ProductCode;
                                engine.Info.ProductCode = Guid.NewGuid().ToString("B").ToUpperInvariant();
                                engine.RemoveMaintenanceCache(); engine.Info.ProductCode = installedCode;
                                using (var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(engine.UninstallRegistryKey))
                                    Assert(key != null && Convert.ToString(key.GetValue("ArpeonProductCode")) == installedCode, "An obsolete maintenance host cannot remove the current uninstall entry");
                            }
                            engine.ValidateInstallation(); Assert(true, "All installed hashes, integration and actual shortcut targets validate");
                            // A corrupt owned file is detected, then native repair restores it.
                            string damaged = Path.Combine(engine.Target, "LEIA-ME.txt"); File.AppendAllText(damaged, "test damage");
                            Reject(engine.ValidateInstallation, "Post-install validation detects corrupted content");
                            engine.Prepare(); uint repaired = await Task.Run(delegate { return engine.Install(false); });
                            Assert(repaired == 0 || repaired == 3010, "Same-version reinstall repairs owned files"); engine.RegisterMaintenance(); engine.ValidateInstallation();
                            string unowned = Path.Combine(engine.Target, "user-owned-sentinel.txt"); File.WriteAllText(unowned, "preserve me");
                            // Force a hash mismatch before MSI starts; no registered installation may change.
                            string expectedHash = engine.Info.MsiHash; engine.Info.MsiHash = new string('0', 64);
                            Reject(engine.Prepare, "Payload corruption is rejected before MSI changes the system");
                            engine.Info.MsiHash = expectedHash; engine.Dispose(); engine.Prepare();
                            var uninstallUi = new InstallerWindow(engine, true); window.Hide();
                            uninstallUi.Window.ShowActivated = false; uninstallUi.Window.ShowInTaskbar = false; uninstallUi.Window.WindowStartupLocation = WindowStartupLocation.Manual; uninstallUi.Window.Left = -20000; uninstallUi.Window.Top = -20000;
                            uninstallUi.Window.Show(); await Task.Delay(200);
                            var uninstallNext = Find<Button>(uninstallUi.Window, "Next");
                            Assert(Convert.ToString(uninstallNext.Content) == "Desinstalar Arpeon", "Uninstaller presents a context-specific removal action");
                            Capture(uninstallUi.Window, "uninstall-welcome", 1);
                            uninstallNext.RaiseEvent(new RoutedEventArgs(Button.ClickEvent)); await Task.Delay(100);
                            var removalActions = Find<StackPanel>(uninstallUi.Window, "NoticeActions");
                            Assert(((Button)removalActions.Children[0]).IsKeyboardFocused, "Uninstall confirmation focuses the safe keep-installed action");
                            ((Button)removalActions.Children[1]).RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                            await Wait(delegate { return Stage(uninstallUi) == 8 || uninstallNext.IsEnabled; }, 1200);
                            Assert(Stage(uninstallUi) == 8 && !engine.Installed, "Custom uninstall waits for native removal and registration validation");
                            Capture(uninstallUi.Window, "uninstall-complete", 1);
                            Assert(!engine.Info.HasMaintenance || !Directory.Exists(engine.CacheDirectory), "Successful uninstall removes its dedicated maintenance cache");
                            Assert(File.ReadAllText(unowned) == "preserve me", "Uninstall preserves an unowned file");
                            // A fresh install cancelled from real MSI events must never report success.
                            engine.Committed = false; engine.CancelRequested = false;
                            engine.Removing = false; engine.Prepare();
                            engine.Progress = delegate(string phase, int? value, string text) { if (phase == "Copiando os arquivos" && value.HasValue && value.Value > 0 && engine.CanCancel) engine.CancelRequested = true; };
                            uint cancelled = await Task.Run(delegate { return engine.Install(false); });
                            Assert(cancelled == 1602 && !engine.Committed && !engine.Installed, "MSI event cancellation rolls back and never reports success");
                            typeof(InstallerWindow).GetField("operationMode", BindingFlags.NonPublic | BindingFlags.Instance).SetValue(ui, "update");
                            Call(ui, "Cancelled");
                            Assert(Find<TextBlock>(window, "Heading").Text.Contains("Atualização interrompida"), "Cancellation keeps its update-specific wording");
                            Assert(Find<TextBlock>(window, "BrandMessage").Text.Contains("interrompida") && !Find<TextBlock>(window, "BrandMessage").Text.Contains("pronto"), "Cancelled operations never show a success message in the brand panel");
                            Capture(window, "cancelled", 1);
                        }
                        File.WriteAllText(Path.Combine(output, "result.txt"), assertions + " assertions passed; lifecycle=" + lifecycle);
                        app.Shutdown(0);
                    } catch (Exception ex) {
                        File.WriteAllText(Path.Combine(output, "failure.txt"), ex.ToString() + "\n" + engine.LastError + "\nLog: " + engine.SessionLog);
                        app.Shutdown(1);
                    }
                };
                return app.Run(window);
            }
        }
    }
}
