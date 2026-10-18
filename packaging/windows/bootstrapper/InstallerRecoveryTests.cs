// Compiled only by test-installer-recovery.ps1. Never embedded in the distributed setup.
using System;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;
using Microsoft.Win32;

namespace Arpeon.Setup
{
    internal static class InstallerRecoveryTests
    {
        private static string output;
        private static string target;
        private static string[] ownedFiles;
        private static string cache;
        private static string oldProduct;
        private static InstallerEngine engine;
        private static InventoryEntry[] previousInventory;
        private static void Assert(bool condition, string message, System.Collections.Generic.IList<string> passed)
        {
            if (!condition) throw new InvalidOperationException(message);
            passed.Add(message);
        }
        [STAThread]
        public static int Main(string[] args)
        {
            output = Path.GetFullPath(args[0]);
            var passed = new System.Collections.Generic.List<string>();
            var result = new System.Collections.Generic.Dictionary<string, object>();
            bool accepted = false;
            try {
                engine = new InstallerEngine();
                previousInventory = new JavaScriptSerializer { MaxJsonLength = 16 * 1024 * 1024 }.Deserialize<InventoryEntry[]>(InstallerEngine.ResourceText("previous-inventory.json"));
                engine.DetectPrevious();
                oldProduct = engine.ExistingProduct;
                target = engine.Target;
                if (String.IsNullOrWhiteSpace(oldProduct) || !engine.IsUpdate) throw new InvalidOperationException("Recovery test requires an older registered fixture version.");
                ownedFiles = engine.Inventory.Select(e => Path.Combine(target, e.path.Replace('/', '\\'))).ToArray();
                cache = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), engine.Info.RegistryRoot.Replace("Software\\", ""), "InstallerCache", oldProduct);
                Assert(Msi.MsiQueryProductState(oldProduct) == 5, "Previous fixture product starts registered", passed);
                Assert(engine.Info.UpgradeCode != null, "Setup metadata includes the stable MSI upgrade identity", passed);
                engine.Prepare();
                uint failedUpdate = engine.Install(false);
                Assert(failedUpdate == 1603, "Fault-injected new MSI fails after the old product is removed", passed);
                Assert(engine.PreviousRecoveryAttempted && engine.PreviousRecoverySucceeded, "Setup automatically reinstalls the verified cached previous MSI", passed);
                Assert(Msi.MsiQueryProductState(oldProduct) == 5, "Previous product registration is restored", passed);
                Assert(Msi.MsiQueryProductState(engine.Info.ProductCode) != 5, "Failed new product is absent after recovery", passed);
                engine.DetectPrevious();
                Assert(engine.ExistingVersion == ReadBundleVersion(cache) && engine.ExistingProduct == oldProduct, "Installed marker identifies the restored version and product", passed);
                foreach (var entry in previousInventory) {
                    string path = Path.Combine(target, entry.path.Replace('/', '\\'));
                    if (!File.Exists(path)) throw new InvalidOperationException("Previous payload file is missing: " + entry.path);
                    long actualSize = new FileInfo(path).Length;
                    string actualHash = Hash(path);
                    if (actualSize != entry.size || actualHash != entry.sha256)
                        throw new InvalidOperationException("Previous payload differs: " + entry.path + " expected size=" + entry.size + " hash=" + entry.sha256 + " actual size=" + actualSize + " hash=" + actualHash);
                }
                Assert(true, "Previous payload hashes match the pre-upgrade fixture", passed);
                accepted = true;
            } catch (Exception ex) {
                result["failure"] = ex.ToString();
                if (engine != null) result["sessionLog"] = engine.SessionLog;
            } finally {
                bool isolatedTarget = engine != null && engine.Info.RegistryRoot.Contains("InstallerTest") && InstallerEngine.Within(target, Path.Combine(Environment.CurrentDirectory, "apps", "desktop", "build"));
                if (isolatedTarget && Msi.MsiQueryProductState(oldProduct) == 5) {
                    try {
                        uint removal = Msi.MsiConfigureProductEx(oldProduct, 0, 2, "REBOOT=ReallySuppress");
                        if (removal != 0 || Msi.MsiQueryProductState(oldProduct) == 5) throw new InvalidOperationException("Cannot clean the isolated recovered product (" + removal + ").");
                        foreach (string file in ownedFiles) if (File.Exists(file)) File.Delete(file);
                        if (Directory.Exists(target)) Directory.Delete(target, false);
                        foreach (string name in new[] { "ARPEON.msi", "bundle.json", "ARPEON-Maintenance.exe" }) {
                            string file = Path.Combine(cache, name); if (File.Exists(file)) File.Delete(file);
                        }
                        if (Directory.Exists(cache)) Directory.Delete(cache, false);
                        Assert(Msi.MsiQueryProductState(oldProduct) != 5, "Recovered fixture is removed through Windows Installer", passed);
                        result["cleanup"] = true;
                    } catch (Exception ex) { result["cleanup"] = false; result["cleanupFailure"] = ex.ToString(); }
                } else result["cleanup"] = !accepted && Msi.MsiQueryProductState(oldProduct) != 5;
                if (engine != null) engine.Dispose();
                result["passed"] = passed;
                result["assertionCount"] = passed.Count;
                result["automaticRecovery"] = accepted;
                File.WriteAllText(Path.Combine(output, "result.json"), new JavaScriptSerializer().Serialize(result), new UTF8Encoding(false));
            }
            return accepted && Convert.ToBoolean(result["cleanup"]) ? 0 : 1;
        }
        private static string ReadBundleVersion(string path)
        {
            var bundle = new JavaScriptSerializer().Deserialize<BundleInfo>(File.ReadAllText(Path.Combine(path, "bundle.json")));
            return bundle.Version;
        }
        private static string Hash(string path)
        {
            using (var sha = SHA256.Create()) using (var stream = File.OpenRead(path))
                return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
        }
    }
}
