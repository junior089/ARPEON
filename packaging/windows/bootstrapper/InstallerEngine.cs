using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using Microsoft.Win32;

namespace Arpeon.Setup
{
    public sealed class BundleInfo
    {
        public string Version { get; set; }
        public string ProductName { get; set; }
        public string ProductCode { get; set; }
        public string UpgradeCode { get; set; }
        public string RegistryRoot { get; set; }
        public string InstallFolder { get; set; }
        public string MsiHash { get; set; }
        public long MsiSize { get; set; }
        public long InstalledSize { get; set; }
        public bool HasMaintenance { get; set; }
    }

    public sealed class InventoryEntry
    {
        public string path { get; set; }
        public string sha256 { get; set; }
        public long size { get; set; }
    }

    // MSI ticks describe the current native phase, not elapsed time or a fabricated overall total.
    public sealed class NativeProgress
    {
        public long Total { get; private set; }
        public long Position { get; private set; }
        public bool Backwards { get; private set; }
        public bool Estimating { get; private set; }
        private int actionTicks;
        public void ResetAction() { actionTicks = 0; }
        public void Message(int kind, int ticks, int direction, int script)
        {
            if (kind == 0) {
                Total = Math.Max(0, ticks); Backwards = direction == 1;
                Position = Backwards ? Total : 0; Estimating = script == 1; actionTicks = 0;
            } else if (kind == 1) actionTicks = direction == 0 ? 0 : ticks;
            else if (kind == 2) Position = Math.Max(0, Math.Min(Total, Position + (Backwards ? -1L : 1L) * ticks));
            else if (kind == 3) Total = Math.Max(0, Total + ticks);
        }
        public void ActionData() { Message(2, actionTicks, 0, 0); }
        public int Percent { get { return Total <= 0 ? 0 : (int)Math.Max(0, Math.Min(100, 100.0 * Position / Total)); } }
    }

    internal static class Msi
    {
        [UnmanagedFunctionPointer(CallingConvention.StdCall)]
        internal delegate int RecordHandler(IntPtr context, uint messageType, uint record);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] internal static extern uint MsiInstallProduct(string package, string properties);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] internal static extern uint MsiConfigureProductEx(string product, int level, int state, string properties);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] internal static extern uint MsiGetProductInfo(string product, string property, StringBuilder value, ref uint size);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] internal static extern uint MsiOpenDatabase(string path, IntPtr persist, out uint database);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] internal static extern uint MsiDatabaseOpenView(uint database, string query, out uint view);
        [DllImport("msi.dll")] internal static extern uint MsiViewExecute(uint view, uint record);
        [DllImport("msi.dll")] internal static extern uint MsiViewFetch(uint view, out uint record);
        [DllImport("msi.dll")] internal static extern uint MsiCloseHandle(uint handle);
        [DllImport("msi.dll")] internal static extern uint MsiSetInternalUI(uint level, IntPtr owner);
        [DllImport("msi.dll")] internal static extern uint MsiSetExternalUIRecord(RecordHandler handler, uint filter, IntPtr context, out IntPtr previous);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] internal static extern uint MsiEnableLog(uint mode, string file, uint attributes);
        [DllImport("msi.dll")] internal static extern int MsiRecordGetInteger(uint record, uint field);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] internal static extern uint MsiRecordGetString(uint record, uint field, StringBuilder text, ref uint length);
        [DllImport("msi.dll", CharSet = CharSet.Unicode)] internal static extern int MsiQueryProductState(string product);
        internal static string Field(uint record, uint field)
        {
            uint size = 4096; var text = new StringBuilder((int)size);
            uint result = MsiRecordGetString(record, field, text, ref size);
            if (result == 234) { text = new StringBuilder((int)size + 1); size++; MsiRecordGetString(record, field, text, ref size); }
            return text.ToString();
        }
    }

    internal sealed class InstallerEngine : IDisposable
    {
        internal readonly BundleInfo Info;
        internal readonly InventoryEntry[] Inventory;
        internal readonly string SessionLog;
        internal string MsiLog;
        internal string Target;
        internal bool Desktop = true, Menu = true;
        internal string ExistingVersion = "", ExistingProduct = "";
        internal bool Installed { get { return Msi.MsiQueryProductState(Info.ProductCode) == 5; } }
        internal bool IsUpdate { get { Version previous; return Version.TryParse(ExistingVersion, out previous) && previous < new Version(Info.Version); } }
        internal string CacheDirectory { get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), Info.RegistryRoot.Replace("Software\\", ""), "InstallerCache", Info.ProductCode); } }
        internal string UninstallRegistryKey { get { return @"Software\Microsoft\Windows\CurrentVersion\Uninstall\ARPEON.Setup." + Info.RegistryRoot.Replace("Software\\", "").Replace('\\', '.'); } }
        internal volatile bool CancelRequested;
        internal volatile bool CanCancel = true;
        internal bool Committed;
        internal bool Removing;
        internal bool PreviousRecoveryAttempted;
        internal bool PreviousRecoverySucceeded;
        internal bool PreviousStillInstalled;
        internal string PreviousRecoveryMessage
        {
            get {
                if (PreviousRecoverySucceeded) return "A versão anterior foi restaurada e confirmada pelo Windows.";
                if (PreviousStillInstalled) return "A versão anterior continua instalada.";
                if (PreviousRecoveryAttempted) return "A restauração automática da versão anterior falhou. Consulte o registro técnico em " + SessionLog;
                return "";
            }
        }
        internal string LastError = "";
        internal Action<string, int?, string> Progress;
        private string staging, package;
        private bool ownsPackage;
        private readonly object logLock = new object();
        private readonly NativeProgress native = new NativeProgress();
        private Msi.RecordHandler callback;
        private long lastUpdate;
        private string action = "", currentFile = "";

        internal static string ResourceText(string name)
        {
            using (var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
            using (var reader = new StreamReader(stream, Encoding.UTF8)) return reader.ReadToEnd();
        }
        internal InstallerEngine()
        {
            var json = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024 };
            Info = json.Deserialize<BundleInfo>(ResourceText("bundle.json"));
            Inventory = json.Deserialize<InventoryEntry[]>(ResourceText("inventory.json"));
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            Target = Path.Combine(local, Info.InstallFolder);
            string logRoot = Path.Combine(local, Info.RegistryRoot.Replace("Software\\", ""), "InstallerLogs");
            Directory.CreateDirectory(logRoot);
            SessionLog = Path.Combine(logRoot, DateTime.Now.ToString("yyyyMMdd-HHmmss") + "-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".log");
            Log("ARPEON Setup " + Info.Version + "; " + Environment.OSVersion + "; x64=" + Environment.Is64BitProcess);
            DetectPrevious();
        }
        internal void Log(string text)
        {
            lock (logLock) File.AppendAllText(SessionLog, DateTime.Now.ToString("o") + " " + text + Environment.NewLine, Encoding.UTF8);
        }
        internal void DetectPrevious()
        {
            ExistingVersion = ""; ExistingProduct = "";
            using (var root = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64))
            using (var key = root.OpenSubKey(Info.RegistryRoot + "\\Installation")) {
                if (key == null) return;
                ExistingVersion = Convert.ToString(key.GetValue("Version", ""));
                ExistingProduct = Convert.ToString(key.GetValue("ProductCode", ""));
                string previous = Convert.ToString(key.GetValue("InstallLocation", ""));
                if (!String.IsNullOrWhiteSpace(previous)) Target = Path.GetFullPath(previous);
                Desktop = Convert.ToString(key.GetValue("DesktopShortcut", "1")) == "1";
                Menu = Convert.ToString(key.GetValue("MenuShortcut", "1")) == "1";
            }
        }
        internal string CheckOperatingSystem()
        {
            using (var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows NT\CurrentVersion")) {
                int build;
                if (key == null || !Int32.TryParse(Convert.ToString(key.GetValue("CurrentBuildNumber")), out build) || build < 10240)
                    throw new InvalidOperationException("Este pacote requer Windows 10 ou posterior.");
                return "Windows · build " + build;
            }
        }
        internal string CheckArchitecture()
        {
            string arch = Environment.GetEnvironmentVariable("PROCESSOR_ARCHITEW6432") ?? Environment.GetEnvironmentVariable("PROCESSOR_ARCHITECTURE");
            if (!Environment.Is64BitOperatingSystem || !String.Equals(arch, "AMD64", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Este pacote inclui componentes nativos para Windows x64. ARM64 não está qualificado.");
            return "x64 · componentes compatíveis";
        }
        internal long AvailableSpace(string path)
        {
            return new DriveInfo(Path.GetPathRoot(Path.GetFullPath(path))).AvailableFreeSpace;
        }
        internal string CheckSpace()
        {
            long available = AvailableSpace(Target);
            // Allow the payload, Windows Installer cache and rollback copies on the target volume.
            long required = Removing ? 16L * 1024 * 1024 : checked(Info.InstalledSize * 2 + Info.MsiSize + 64L * 1024 * 1024);
            if (available < required) throw new IOException("Espaço insuficiente em " + Path.GetPathRoot(Target) + ". Libere pelo menos " + Size(required) + ".");
            if (AvailableSpace(Path.GetTempPath()) < (Removing ? 16L * 1024 * 1024 : Info.MsiSize + 64L * 1024 * 1024))
                throw new IOException("Espaço insuficiente na unidade de arquivos temporários.");
            return Size(available) + " livres · reserva de " + Size(required);
        }
        internal static string Size(long value) { return (value / (1024.0 * 1024 * 1024)).ToString("0.00") + " GB"; }
        internal void ValidateTarget()
        {
            if (String.IsNullOrWhiteSpace(Target) || Target.IndexOfAny(new[] { '"', '\r', '\n' }) >= 0 || !Path.IsPathRooted(Target))
                throw new IOException("Escolha um caminho absoluto válido para o aplicativo.");
            Target = Path.GetFullPath(Target).TrimEnd(Path.DirectorySeparatorChar);
            if (Target.Length < 4 || Target.StartsWith(@"\\", StringComparison.Ordinal) || Target.Length > 180)
                throw new IOException("Escolha uma pasta local com caminho de até 180 caracteres.");
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            foreach (string reserved in new[] { Path.Combine(local, "ARPEON"), Path.Combine(profile, ".arpeon"), Environment.GetFolderPath(Environment.SpecialFolder.Windows) })
                if (Within(Target, reserved) || Within(reserved, Target)) throw new IOException("Escolha uma pasta de aplicativo separada dos dados pessoais e das pastas do sistema.");
            string ancestor = Target;
            while (!String.IsNullOrEmpty(ancestor)) {
                if (Directory.Exists(ancestor) && (File.GetAttributes(ancestor) & FileAttributes.ReparsePoint) != 0)
                    throw new IOException("Escolha uma pasta sem links ou redirecionamentos de diretório.");
                ancestor = Path.GetDirectoryName(ancestor);
            }
        }
        internal static bool Within(string child, string parent)
        {
            string a = Path.GetFullPath(child).TrimEnd('\\') + "\\";
            string b = Path.GetFullPath(parent).TrimEnd('\\') + "\\";
            return a.StartsWith(b, StringComparison.OrdinalIgnoreCase);
        }
        internal string CheckPermissions()
        {
            ValidateTarget();
            string ancestor = Target;
            while (!Directory.Exists(ancestor)) ancestor = Path.GetDirectoryName(ancestor);
            string probe = Path.Combine(ancestor, ".arpeon-write-" + Guid.NewGuid().ToString("N"));
            using (var stream = new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1, FileOptions.DeleteOnClose)) stream.WriteByte(0);
            return "Instalação para este usuário · sem elevação";
        }
        internal string CheckDependencies()
        {
            foreach (string file in new[] { "ARPEON.exe", "runtime/bin/server/jvm.dll", "tools/cef/jcef.dll", "tools/audiveris-5.11.0/app/audiveris.jar" })
                if (!Inventory.Any(e => String.Equals(e.path, file, StringComparison.OrdinalIgnoreCase))) throw new IOException("O pacote não contém um componente obrigatório: " + file);
            if (FileVersionInfo.GetVersionInfo(Path.Combine(Environment.SystemDirectory, "msi.dll")).FileMajorPart < 5)
                throw new InvalidOperationException("O Windows Installer 5 ou posterior é necessário.");
            return "Java, Chromium e Audiveris incluídos · MSI disponível";
        }
        internal string CheckPrevious()
        {
            using (var root = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64))
            using (var key = root.OpenSubKey(Info.RegistryRoot + "\\Installation"))
                if (key != null) throw new InvalidOperationException("Existe uma instalação para todos os usuários. Use a manutenção dela em Aplicativos do Windows.");
            Version previous;
            if (Version.TryParse(ExistingVersion, out previous) && previous > new Version(Info.Version))
                throw new InvalidOperationException("A versão " + previous + " já está instalada. Abra essa versão ou use sua manutenção no Windows.");
            foreach (var process in Process.GetProcessesByName("ARPEON")) {
                using (process) {
                    try { if (process.MainModule != null && Within(process.MainModule.FileName, Target)) throw new InvalidOperationException("Feche o Arpeon antes de continuar."); }
                    catch (System.ComponentModel.Win32Exception) { throw new InvalidOperationException("Há um Arpeon em execução. Feche o aplicativo antes de continuar."); }
                }
            }
            return ExistingVersion.Length == 0 ? "Nenhuma instalação registrada para este usuário" : "Versão " + ExistingVersion + " · dados pessoais preservados";
        }
        private void ThrowIfCancelled() { if (CancelRequested) throw new OperationCanceledException(); }
        private void Report(string phase, int? percent, string detail)
        {
            if (Progress != null) Progress(phase, percent, detail);
        }
        internal void Prepare()
        {
            ThrowIfCancelled();
            ValidateTarget(); CheckSpace(); CheckPermissions(); CheckPrevious();
            Dispose();
            if (Removing) {
                if (!Installed) throw new IOException("Esta versão não está registrada para remoção.");
                Report("Validando a desinstalação", null, "Usando o registro e o pacote protegido do Windows Installer.");
                return;
            }
            staging = Path.Combine(Path.GetTempPath(), "ArpeonSetup-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(staging);
            package = Path.Combine(staging, "ARPEON.msi");
            ownsPackage = true;
            using (var input = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.msi") ?? File.OpenRead(Path.Combine(CacheDirectory, "ARPEON.msi")))
            using (var output = new FileStream(package, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                byte[] buffer = new byte[1024 * 1024]; int count; long copied = 0;
                while ((count = input.Read(buffer, 0, buffer.Length)) != 0) {
                    ThrowIfCancelled(); output.Write(buffer, 0, count); copied += count;
                    Report("Extraindo o pacote", (int)(copied * 100 / Info.MsiSize), Size(copied) + " de " + Size(Info.MsiSize));
                }
            }
            Report("Validando integridade", null, "Conferindo SHA-256 do pacote de instalação.");
            if (!String.Equals(Hash(package), Info.MsiHash, StringComparison.OrdinalIgnoreCase)) throw new IOException("A integridade do instalador não pôde ser confirmada. Obtenha uma nova cópia do pacote.");
            ThrowIfCancelled(); Log("MSI SHA-256 verified.");
        }
        internal static string Hash(string path)
        {
            using (var algorithm = SHA256.Create()) using (var stream = File.OpenRead(path))
                return BitConverter.ToString(algorithm.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
        }
        internal uint Install(bool remove, bool autoLaunch = false)
        {
            ThrowIfCancelled(); LastError = ""; CanCancel = true;
            PreviousRecoveryAttempted = PreviousRecoverySucceeded = PreviousStillInstalled = false;
            string properties = "REBOOT=ReallySuppress ARPEON_AUTOLAUNCH=" + (autoLaunch ? "1" : "0");
            if (Info.HasMaintenance) properties += " ARPSYSTEMCOMPONENT=1";
            if (!remove) {
                properties += " INSTALLDIR=\"" + Target + "\" INSTALLDESKTOPSHORTCUT=" + (Desktop ? "1" : "0") + " INSTALLMENUSHORTCUT=" + (Menu ? "1" : "0");
                if (Installed) properties += " REINSTALL=ALL REINSTALLMODE=vamus";
            }
            Log("MSI begin; remove=" + remove + "; target=" + Target);
            uint code = ExecuteMsi(package, remove, properties);
            Log("MSI returned " + code); Committed = code == 0 || code == 3010;
            if (!remove && !Committed && IsUpdate) RestorePreviousVersion();
            return code;
        }
        internal uint InstallQuiet(bool autoLaunch)
        {
            try {
                Prepare();
                uint code = Install(false, autoLaunch);
                if (code != 0 && code != 3010) return code;
                RegisterMaintenance();
                ValidateInstallation();
                CleanupObsoleteMaintenance();
                if (autoLaunch && code != 3010) Launch();
                return code;
            } catch (Exception ex) {
                Log("Quiet installation failed: " + ex);
                return 1603;
            } finally { Dispose(); }
        }
        private uint ExecuteMsi(string msiPath, bool remove, string properties)
        {
            MsiLog = Path.Combine(Path.GetTempPath(), "ArpeonMsi-" + Guid.NewGuid().ToString("N") + ".log");
            uint previousLevel = Msi.MsiSetInternalUI(2, IntPtr.Zero);
            IntPtr previous;
            callback = OnMsiMessage;
            uint registered = Msi.MsiSetExternalUIRecord(callback, 0x1fff | (1u << 25), IntPtr.Zero, out previous);
            if (registered != 0) throw new IOException("Não foi possível acompanhar o Windows Installer (" + registered + ").");
            try {
                uint logging = Msi.MsiEnableLog(0x1fff, MsiLog, 1);
                if (logging != 0) throw new IOException("Não foi possível criar o registro técnico da instalação (" + logging + ").");
                return remove ? Msi.MsiConfigureProductEx(Info.ProductCode, 0, 2, properties) : Msi.MsiInstallProduct(msiPath, properties);
            } finally {
                Msi.MsiEnableLog(0, null, 0); Msi.MsiSetExternalUIRecord(null, 0, IntPtr.Zero, out previous);
                Msi.MsiSetInternalUI(previousLevel, IntPtr.Zero); GC.KeepAlive(callback);
                string archivedLog = SessionLog + "." + Path.GetFileName(MsiLog);
                if (File.Exists(MsiLog)) {
                    try { File.Copy(MsiLog, archivedLog, false); File.Delete(MsiLog); MsiLog = archivedLog; Log("Native log: " + archivedLog); }
                    catch (IOException) { Log("MSI log retained at " + MsiLog); }
                }
                CanCancel = true;
            }
        }
        private void RestorePreviousVersion()
        {
            if (Msi.MsiQueryProductState(ExistingProduct) == 5) {
                PreviousStillInstalled = true;
                Log("Failed update left the previous product registered; no reinstall was needed.");
                return;
            }
            PreviousRecoveryAttempted = true;
            string originalError = LastError;
            bool originalCancel = CancelRequested;
            string previousMsi = null;
            CanCancel = false; CancelRequested = false;
            try {
                string previousSource = FindPreviousMsi();
                previousMsi = Path.Combine(staging, "ARPEON-Previous.msi");
                File.Copy(previousSource, previousMsi, false);
                if (!String.Equals(Hash(previousMsi), Hash(previousSource), StringComparison.OrdinalIgnoreCase)) throw new IOException("A cópia temporária do pacote anterior não passou pela verificação de integridade.");
                string restoreProperties = "REBOOT=ReallySuppress ARPEON_AUTOLAUNCH=0 ARPSYSTEMCOMPONENT=1 INSTALLDIR=\"" + Target + "\" INSTALLDESKTOPSHORTCUT=" + (Desktop ? "1" : "0") + " INSTALLMENUSHORTCUT=" + (Menu ? "1" : "0");
                Log("Restoring previous version " + ExistingVersion + " from its verified MSI cache.");
                uint restored = ExecuteMsi(previousMsi, false, restoreProperties);
                string version, location;
                ReadInstalledIdentity(out version, out location);
                PreviousRecoverySucceeded = (restored == 0 || restored == 3010) && Msi.MsiQueryProductState(ExistingProduct) == 5 &&
                    version == ExistingVersion && String.Equals(Path.GetFullPath(location).TrimEnd('\\'), Target.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);
                if (!PreviousRecoverySucceeded) throw new IOException("O Windows não confirmou o retorno à versão " + ExistingVersion + " (código " + restored + ").");
                Log("Previous version recovery verified.");
            } catch (Exception ex) {
                Log("Previous version recovery failed: " + ex);
                LastError = originalError + (originalError.Length == 0 ? "" : " ") + "A restauração automática da versão " + ExistingVersion + " falhou. " + ex.Message + " Consulte o registro técnico: " + SessionLog;
            } finally {
                if (previousMsi != null && File.Exists(previousMsi)) {
                    try { File.Delete(previousMsi); } catch (IOException ex) { Log("Previous MSI staging cleanup deferred: " + ex.Message); }
                }
                CanCancel = true; CancelRequested = originalCancel;
            }
        }
        private string FindPreviousMsi()
        {
            Guid code;
            if (!Guid.TryParse(ExistingProduct, out code)) throw new IOException("O código da instalação anterior não é válido.");
            string root = Info.RegistryRoot.Replace("Software\\", "");
            string cache = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), root, "InstallerCache", code.ToString("B").ToUpperInvariant());
            ValidateCachePath(cache);
            string cachedMsi = Path.Combine(cache, "ARPEON.msi");
            string marker = Path.Combine(cache, "bundle.json");
            if (File.Exists(cachedMsi) && File.Exists(marker)) {
                var json = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024 };
                var previous = json.Deserialize<BundleInfo>(File.ReadAllText(marker, Encoding.UTF8));
                if (previous != null && previous.HasMaintenance && String.Equals(previous.ProductCode, ExistingProduct, StringComparison.OrdinalIgnoreCase) &&
                    previous.Version == ExistingVersion && previous.RegistryRoot == Info.RegistryRoot && previous.UpgradeCode == Info.UpgradeCode &&
                    previous.MsiSize == new FileInfo(cachedMsi).Length && String.Equals(previous.MsiHash, Hash(cachedMsi), StringComparison.OrdinalIgnoreCase)) {
                    ValidatePreviousMsi(cachedMsi); return cachedMsi;
                }
                Log("Cached maintenance metadata did not match the installed previous product; checking the protected Windows Installer cache.");
            }
            StringBuilder localPackage = new StringBuilder(32768); uint size = (uint)localPackage.Capacity;
            uint result = Msi.MsiGetProductInfo(ExistingProduct, "LocalPackage", localPackage, ref size);
            if (result == 234) { localPackage = new StringBuilder((int)size + 1); size++; result = Msi.MsiGetProductInfo(ExistingProduct, "LocalPackage", localPackage, ref size); }
            if (result != 0 || String.IsNullOrWhiteSpace(localPackage.ToString())) throw new IOException("O cache de manutenção anterior não está disponível e o Windows não encontrou o pacote local (" + result + ").");
            string local = Path.GetFullPath(localPackage.ToString());
            string windowsInstaller = Path.GetFullPath(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "Installer")) + Path.DirectorySeparatorChar;
            if (!local.StartsWith(windowsInstaller, StringComparison.OrdinalIgnoreCase) || !File.Exists(local) || (File.GetAttributes(local) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("O pacote da instalação anterior não está no cache protegido do Windows Installer.");
            ValidatePreviousMsi(local); return local;
        }
        private void ValidatePreviousMsi(string path)
        {
            if (!File.Exists(path) || new FileInfo(path).Length == 0 || (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("O pacote da instalação anterior não está íntegro.");
            if (!String.Equals(MsiProperty(path, "ProductCode"), ExistingProduct, StringComparison.OrdinalIgnoreCase) ||
                MsiProperty(path, "ProductVersion") != ExistingVersion ||
                !String.Equals(MsiProperty(path, "UpgradeCode"), Info.UpgradeCode, StringComparison.OrdinalIgnoreCase))
                throw new IOException("A identidade do pacote em cache não corresponde à instalação anterior.");
        }
        private static string MsiProperty(string path, string property)
        {
            uint database = 0, view = 0, record = 0;
            try {
                uint result = Msi.MsiOpenDatabase(path, IntPtr.Zero, out database);
                if (result != 0) throw new IOException("Não foi possível abrir o MSI em cache (" + result + ").");
                result = Msi.MsiDatabaseOpenView(database, "SELECT `Value` FROM `Property` WHERE `Property`='" + property.Replace("'", "''") + "'", out view);
                if (result != 0 || Msi.MsiViewExecute(view, 0) != 0 || Msi.MsiViewFetch(view, out record) != 0) throw new IOException("O pacote em cache não contém " + property + ".");
                uint size = 4096; var value = new StringBuilder((int)size);
                result = Msi.MsiRecordGetString(record, 1, value, ref size);
                if (result == 234) { value = new StringBuilder((int)size + 1); size++; result = Msi.MsiRecordGetString(record, 1, value, ref size); }
                if (result != 0) throw new IOException("Não foi possível ler " + property + " do pacote em cache.");
                return value.ToString();
            } finally {
                if (record != 0) Msi.MsiCloseHandle(record);
                if (view != 0) Msi.MsiCloseHandle(view);
                if (database != 0) Msi.MsiCloseHandle(database);
            }
        }
        private void ReadInstalledIdentity(out string version, out string location)
        {
            using (var root = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64))
            using (var key = root.OpenSubKey(Info.RegistryRoot + "\\Installation")) {
                version = key == null ? "" : Convert.ToString(key.GetValue("Version", ""));
                location = key == null ? "" : Convert.ToString(key.GetValue("InstallLocation", ""));
            }
        }
        private int OnMsiMessage(IntPtr context, uint messageType, uint record)
        {
            try {
                uint kind = messageType & 0xff000000;
                if (kind == 0x08000000) {
                    native.ResetAction(); action = Msi.Field(record, 1); currentFile = "";
                    Report(ActionLabel(action), null, Msi.Field(record, 2));
                } else if (kind == 0x0a000000) {
                    native.Message(Msi.MsiRecordGetInteger(record, 1), Msi.MsiRecordGetInteger(record, 2), Msi.MsiRecordGetInteger(record, 3), Msi.MsiRecordGetInteger(record, 4));
                    Tick();
                } else if (kind == 0x09000000) {
                    if (action == "InstallFiles") currentFile = Msi.Field(record, 1);
                    native.ActionData(); Tick();
                } else if (kind == 0x01000000 || kind == 0x02000000) {
                    LastError = Msi.Field(record, 0) + " " + Msi.Field(record, 1); Log("MSI error: " + LastError);
                    // Honor the message's button contract. Never accept a retry/ignore silently.
                    uint buttons = messageType & 0xf;
                    return buttons == 0 ? 1 : (buttons == 4 ? 7 : 2);
                } else if (kind == 0x05000000 || kind == 0x19000000 || kind == 0x07000000) {
                    LastError = "Arquivos em uso ou espaço insuficiente. Feche o Arpeon e confira o espaço disponível antes de tentar novamente.";
                    return 2;
                } else if (kind == 0x0b000000 && Msi.MsiRecordGetInteger(record, 1) == 2) {
                    CanCancel = Msi.MsiRecordGetInteger(record, 2) != 0;
                } else if (kind == 0x06000000) return 0; // Source resolution belongs to MSI.
                // Keep the native rollback running; cancellation cannot interrupt its restoration.
                if (CancelRequested && CanCancel && !native.Backwards && (kind == 0x0a000000 || kind == 0x09000000)) return 2;
                return 1;
            } catch (Exception ex) { LastError = ex.Message; return 2; }
        }
        private void Tick()
        {
            long now = Stopwatch.GetTimestamp();
            if (now - lastUpdate < Stopwatch.Frequency / 15) return;
            lastUpdate = now;
            Report(native.Backwards ? "Restaurando alterações" : ActionLabel(action), native.Total > 0 && !native.Estimating ? (int?)native.Percent : null,
                native.Backwards ? "Aguarde a recuperação do Windows Installer." : currentFile.Length > 0 ? currentFile : "Progresso da fase atual · Windows Installer");
        }
        private static string ActionLabel(string value)
        {
            switch (value) {
                case "InstallFiles": return "Copiando os arquivos";
                case "RemoveFiles": return "Removendo arquivos do aplicativo";
                case "CreateShortcuts": return "Criando os atalhos";
                case "WriteRegistryValues": return "Configurando o Arpeon";
                case "RegisterProduct": case "PublishProduct": return "Registrando o aplicativo";
                case "InstallFinalize": return "Aplicando a instalação";
                case "RemoveExistingProducts": return "Atualizando a versão anterior";
                default: return "Preparando componentes";
            }
        }
        internal void ValidateInstallation()
        {
            ValidateTarget();
            string root = Path.GetFullPath(Target).TrimEnd('\\') + "\\";
            int count = 0;
            foreach (var entry in Inventory) {
                ThrowIfCancelled();
                string path = Path.GetFullPath(Path.Combine(root, entry.path.Replace('/', '\\')));
                if (!path.StartsWith(root, StringComparison.OrdinalIgnoreCase)) throw new IOException("Caminho inválido no inventário do pacote.");
                string ancestor = path;
                while (ancestor != null && Within(ancestor, Target)) {
                    if ((File.GetAttributes(ancestor) & FileAttributes.ReparsePoint) != 0) throw new IOException("Arquivo redirecionado após instalação: " + entry.path);
                    ancestor = Path.GetDirectoryName(ancestor);
                }
                if (new FileInfo(path).Length != entry.size || !String.Equals(Hash(path), entry.sha256, StringComparison.OrdinalIgnoreCase))
                    throw new IOException("O arquivo " + entry.path + " não passou pela verificação de integridade. Use Reparar arquivos.");
                count++; Report("Conferindo arquivos instalados", count * 100 / Inventory.Length, count + " de " + Inventory.Length + " arquivos · SHA-256");
            }
            if (!Installed) throw new IOException("O registro do aplicativo no Windows não foi confirmado.");
            using (var key = Registry.CurrentUser.OpenSubKey(Info.RegistryRoot + "\\Installation")) {
                if (key == null || Convert.ToString(key.GetValue("Version")) != Info.Version || !String.Equals(Path.GetFullPath(Convert.ToString(key.GetValue("InstallLocation"))).TrimEnd('\\'), Target.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase))
                    throw new IOException("O registro da versão ou da pasta instalada não corresponde ao pacote.");
            }
            // Inspect the actual shortcut targets, not just the existence of a .lnk.
            string productName = Info.RegistryRoot.Contains("InstallerTest") ? "ARPEON Lifecycle Test" + (Info.RegistryRoot.EndsWith("InstallerTest", StringComparison.Ordinal) ? "" : " " + Info.RegistryRoot.Split('\\').Last()) : "ARPEON";
            if (Desktop) CheckShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), productName + ".lnk"));
            if (Menu) CheckShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), productName, productName + ".lnk"));
            Log("All " + Inventory.Length + " hashes, registration and selected shortcuts verified.");
        }
        private void CheckShortcut(string path)
        {
            if (!File.Exists(path)) throw new IOException("O atalho não foi encontrado: " + path);
            object shell = null, link = null;
            try {
                shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell"));
                link = shell.GetType().InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { path });
                string target = Convert.ToString(link.GetType().InvokeMember("TargetPath", BindingFlags.GetProperty, null, link, null));
                if (!String.Equals(Path.GetFullPath(target), Path.Combine(Target, "ARPEON.exe"), StringComparison.OrdinalIgnoreCase)) throw new IOException("O destino do atalho está incorreto: " + path);
            } finally {
                if (link != null) Marshal.FinalReleaseComObject(link);
                if (shell != null) Marshal.FinalReleaseComObject(shell);
            }
        }
        internal void Launch()
        {
            string exe = Path.Combine(Target, "ARPEON.exe");
            if (!File.Exists(exe)) throw new IOException("O aplicativo não foi encontrado. Execute Reparar arquivos.");
            Process.Start(new ProcessStartInfo(exe) { WorkingDirectory = Target, UseShellExecute = true });
        }
        internal void RegisterMaintenance()
        {
            if (!Info.HasMaintenance) return;
            Report("Preparando a manutenção", null, "Guardando o pacote para reparações e desinstalação offline.");
            ValidateCachePath(CacheDirectory);
            Directory.CreateDirectory(CacheDirectory);
            string cachedPackage = Path.Combine(CacheDirectory, "ARPEON.msi");
            if (!File.Exists(cachedPackage) || Hash(cachedPackage) != Info.MsiHash) File.Copy(package, cachedPackage, true);
            string executable = Path.Combine(CacheDirectory, "ARPEON-Maintenance.exe");
            using (var source = Assembly.GetExecutingAssembly().GetManifestResourceStream("maintenance.exe")) {
                // A thin maintenance host already has its own executable in this cache.
                if (source != null) using (var destination = File.Create(executable)) source.CopyTo(destination);
                else if (!File.Exists(executable)) throw new IOException("A ferramenta de manutenção não foi encontrada. Execute o Setup completo para recuperá-la.");
            }
            File.WriteAllText(Path.Combine(CacheDirectory, "bundle.json"), ResourceText("bundle.json"), Encoding.UTF8);
            using (var root = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64))
            using (var key = root.CreateSubKey(UninstallRegistryKey)) {
                key.SetValue("DisplayName", Info.ProductName ?? "ARPEON", RegistryValueKind.String);
                key.SetValue("DisplayVersion", Info.Version, RegistryValueKind.String);
                key.SetValue("ArpeonProductCode", Info.ProductCode, RegistryValueKind.String);
                key.SetValue("Publisher", "ARPEON", RegistryValueKind.String);
                key.SetValue("InstallLocation", Target, RegistryValueKind.String);
                key.SetValue("DisplayIcon", Path.Combine(Target, "ARPEON.exe"), RegistryValueKind.String);
                key.SetValue("EstimatedSize", (int)(Info.InstalledSize / 1024), RegistryValueKind.DWord);
                key.SetValue("UninstallString", "\"" + executable + "\" --uninstall", RegistryValueKind.String);
                key.SetValue("QuietUninstallString", "\"" + executable + "\" --uninstall --quiet", RegistryValueKind.String);
                key.SetValue("ModifyPath", "\"" + executable + "\"", RegistryValueKind.String);
                key.SetValue("WindowsInstaller", 0, RegistryValueKind.DWord);
                key.SetValue("NoModify", 0, RegistryValueKind.DWord);
            }
            Log("Custom offline maintenance registered at " + executable);
        }
        internal void RemoveMaintenanceCache()
        {
            if (!Info.HasMaintenance || Installed) return;
            using (var root = RegistryKey.OpenBaseKey(RegistryHive.CurrentUser, RegistryView.Registry64)) {
                bool owned;
                using (var key = root.OpenSubKey(UninstallRegistryKey)) owned = key != null && String.Equals(Convert.ToString(key.GetValue("ArpeonProductCode")), Info.ProductCode, StringComparison.OrdinalIgnoreCase);
                if (owned) root.DeleteSubKey(UninstallRegistryKey, false);
            }
            DeleteCacheFiles(CacheDirectory);
        }
        private void DeleteCacheFiles(string directory)
        {
            ValidateCachePath(directory);
            foreach (string name in new[] { "ARPEON.msi", "ARPEON-Maintenance.exe", "bundle.json" }) {
                string file = Path.Combine(directory, name);
                try { if (File.Exists(file)) File.Delete(file); }
                catch (IOException ex) { Log("Maintenance cleanup deferred: " + ex.Message); }
            }
            try { if (Directory.Exists(directory)) Directory.Delete(directory, false); }
            catch (IOException ex) { Log("Maintenance cache directory retained: " + ex.Message); }
        }
        private void ValidateCachePath(string directory)
        {
            string parent = Path.GetDirectoryName(CacheDirectory);
            if (!Within(directory, parent)) throw new IOException("A pasta de manutenção está fora do cache dedicado.");
            string ancestor = Path.GetFullPath(directory);
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            while (ancestor != null && Within(ancestor, local)) {
                if (Directory.Exists(ancestor) && (File.GetAttributes(ancestor) & FileAttributes.ReparsePoint) != 0)
                    throw new IOException("A pasta de manutenção contém um redirecionamento de diretório.");
                ancestor = Path.GetDirectoryName(ancestor);
            }
        }
        internal void CleanupObsoleteMaintenance()
        {
            if (!Info.HasMaintenance) return;
            string parent = Path.GetDirectoryName(CacheDirectory);
            foreach (string directory in Directory.GetDirectories(parent)) {
                string code = Path.GetFileName(directory); Guid parsed;
                if (!Guid.TryParse(code, out parsed) || String.Equals(code, Info.ProductCode, StringComparison.OrdinalIgnoreCase) || Msi.MsiQueryProductState(code) == 5) continue;
                string marker = Path.Combine(directory, "bundle.json");
                if (!File.Exists(marker) || (File.GetAttributes(directory) & FileAttributes.ReparsePoint) != 0 || new FileInfo(marker).Length > 16384) continue;
                try {
                    var owner = new JavaScriptSerializer().Deserialize<BundleInfo>(File.ReadAllText(marker));
                    if (owner.RegistryRoot == Info.RegistryRoot && String.Equals(owner.ProductCode, code, StringComparison.OrdinalIgnoreCase)) DeleteCacheFiles(directory);
                } catch (Exception ex) {
                    if (!(ex is IOException || ex is UnauthorizedAccessException || ex is ArgumentException || ex is InvalidOperationException)) throw;
                    Log("Old maintenance cache retained: " + ex.Message);
                }
            }
        }
        public void Dispose()
        {
            // Delete only the two files this session owns; never recursively delete a chosen path.
            if (ownsPackage && package != null && File.Exists(package)) {
                try { File.Delete(package); } catch (IOException) { Log("Temporary MSI remains for cleanup: " + package); }
            }
            if (staging != null && Directory.Exists(staging)) {
                try { Directory.Delete(staging, false); } catch (IOException) { Log("Staging directory is not empty: " + staging); }
            }
            ownsPackage = false; package = null; staging = null;
        }
    }
}
