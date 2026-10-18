using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Automation.Peers;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace Arpeon.Setup
{
    internal sealed class InstallerWindow
    {
        private readonly InstallerEngine engine;
        private readonly Window window;
        private readonly Brush gold = new SolidColorBrush(Color.FromRgb(230, 201, 137));
        private readonly Brush muted = new SolidColorBrush(Color.FromRgb(172, 175, 179));
        private readonly Brush error = new SolidColorBrush(Color.FromRgb(239, 167, 151));
        private readonly StackPanel content, secondary;
        private readonly Button next;
        private readonly DispatcherTimer brandTimer;
        private int brandMessageIndex;
        private readonly List<Tuple<Animatable, DependencyProperty>> brandClocks = new List<Tuple<Animatable, DependencyProperty>>();
        private RotateTransform brandOrbit;
        private System.Windows.Shapes.Ellipse brandNote;
        private Action nextAction;
        private int stage;
        private bool busy, remove, closing, validated;
        private string operationMode = "install";
        private string terminalResult = "";
        private TextBox directory;
        private CheckBox desktop, menu, launch;
        private ProgressBar progressBar;
        private TextBlock percent, operation, detail;
        private string pendingPhase, pendingDetail;
        private int? pendingPercent;
        private int pendingStage;
        private int progressScheduled;
        private readonly object progressLock = new object();
        private readonly string[] names = { "Inicialização", "Boas-vindas", "Verificação", "Personalização", "Preparação", "Instalação", "Validação", "Conclusão" };
        internal Window Window { get { return window; } }

        internal InstallerWindow(InstallerEngine installer, bool uninstall = false)
        {
            engine = installer;
            window = (Window)XamlReader.Parse(InstallerEngine.ResourceText("Installer.xaml"));
            window.Width = Math.Min(window.Width, SystemParameters.WorkArea.Width - 24);
            window.Height = Math.Min(window.Height, SystemParameters.WorkArea.Height - 24);
            window.MinWidth = Math.Min(window.MinWidth, window.Width);
            window.MinHeight = Math.Min(window.MinHeight, window.Height);
            content = Find<StackPanel>("Content"); secondary = Find<StackPanel>("SecondaryActions"); next = Find<Button>("Next");
            foreach (string name in new[] { "Heading", "NoticeTitle" }) {
                var label = Find<TextBlock>(name); label.Focusable = true;
                System.Windows.Input.KeyboardNavigation.SetIsTabStop(label, false);
                AutomationProperties.SetLiveSetting(label, name == "NoticeTitle" ? AutomationLiveSetting.Assertive : AutomationLiveSetting.Polite);
            }
            Find<TextBlock>("VersionLabel").Text = "VERSÃO " + engine.Info.Version + "  /  WINDOWS x64";
            next.Click += delegate { if (nextAction != null) nextAction(); };
            Find<Button>("Minimize").Click += delegate { window.WindowState = WindowState.Minimized; };
            Find<Button>("CloseWindow").Click += delegate { RequestClose(); };
            Find<Button>("Logs").Click += delegate { ShowLogs(); };
            window.Closing += delegate(object sender, System.ComponentModel.CancelEventArgs args) {
                if (!closing && busy) { args.Cancel = true; RequestClose(); }
            };
            window.PreviewKeyDown += delegate(object sender, System.Windows.Input.KeyEventArgs args) {
                if (args.Key == System.Windows.Input.Key.Escape) { RequestClose(); args.Handled = true; }
            };
            engine.Progress = QueueProgress;
            brandTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(7) };
            brandTimer.Tick += delegate { RotateBrandMessage(); };
            window.Closed += delegate { brandTimer.Stop(); };
            SetStage(1, "Afinando os primeiros detalhes", "Inicializando o instalador e lendo os componentes do pacote.");
            SetNext("Inicializando…", null);
            window.Loaded += async delegate { await Task.Yield(); if (uninstall) RemovalWelcome(); else Welcome(); };
        }
        private T Find<T>(string name) where T : class { return (T)window.FindName(name); }
        private void SetStage(int number, string title, string description)
        {
            stage = number; content.Children.Clear(); secondary.Children.Clear(); HideNotice();
            brandMessageIndex = 0; UpdateBrandScene();
            string phaseName = number == 6 ? remove ? "Desinstalação" : operationMode == "update" ? "Atualização" : operationMode == "repair" ? "Reparação" : "Instalação" : names[number - 1];
            Find<TextBlock>("StepLabel").Text = "ETAPA " + number.ToString("00") + " / 08   ·   " + phaseName.ToUpperInvariant();
            Find<TextBlock>("Heading").Text = title; Find<TextBlock>("Description").Text = description;
            var navigation = Find<StackPanel>("StepNavigation"); navigation.Children.Clear();
            for (int i = 1; i <= 8; i++) {
                var mark = new Border { Width = i == number ? 32 : 16, Height = 3, CornerRadius = new CornerRadius(1.5), Margin = new Thickness(0, 0, 5, 0), Background = i <= number ? gold : new SolidColorBrush(Color.FromRgb(54, 59, 63)), ToolTip = i + " · " + names[i - 1] };
                System.Windows.Automation.AutomationProperties.SetName(mark, names[i - 1] + (i == number ? " · etapa atual" : ""));
                navigation.Children.Add(mark);
            }
            if (SystemParameters.ClientAreaAnimation && !SystemParameters.HighContrast) {
                content.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.5, 1, TimeSpan.FromMilliseconds(180)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
            }
            window.Dispatcher.BeginInvoke(DispatcherPriority.ContextIdle, new Action(delegate {
                if (stage != number || number <= 2) return;
                var heading = Find<TextBlock>("Heading"); heading.Focus(); Announce(heading);
            }));
        }
        private void SetNext(string label, Action action)
        {
            next.Content = label; nextAction = action; next.IsEnabled = action != null;
            System.Windows.Automation.AutomationProperties.SetName(next, label.Replace("_", ""));
        }
        private Button Button(string label, Action action)
        {
            var button = new Button { Content = label, Margin = new Thickness(0, 0, 8, 0), Background = Brushes.Transparent, Foreground = muted, Padding = new Thickness(8, 10, 8, 10) };
            button.Click += delegate { action(); }; return button;
        }
        private TextBlock Text(string value, int size, Brush color)
        {
            return new TextBlock { Text = value, FontSize = size, Foreground = color, TextWrapping = TextWrapping.Wrap, LineHeight = size + 8 };
        }
        private void Animate(Animatable target, DependencyProperty property, AnimationTimeline animation)
        {
            if (!SystemParameters.ClientAreaAnimation || SystemParameters.HighContrast) return;
            target.BeginAnimation(property, animation); brandClocks.Add(Tuple.Create(target, property));
        }
        private void UpdateBrandScene()
        {
            StopMotion();
            foreach (var clock in brandClocks) clock.Item1.BeginAnimation(clock.Item2, null);
            brandClocks.Clear();
            var canvas = Find<Canvas>("BrandScene"); canvas.Children.Clear();
            bool scanning = stage == 3 || stage == 7;
            bool rings = stage == 5 || (stage == 8 && terminalResult == "success");
            bool preserved = stage == 8 && terminalResult != "success";
            Find<TextBlock>("BrandTitle").Text = preserved ? "A sua música," : remove ? "Novos caminhos," : stage == 8 ? "Sua próxima" : "Sua música,";
            Find<TextBlock>("BrandAccent").Text = preserved ? "continua aqui." : remove ? "a mesma música." : stage == 8 ? "grande ideia." : "sem limites.";
            if (scanning) {
                for (int i = 0; i < 21; i++) {
                    double height = 35 + 135 * Math.Pow(Math.Sin((i + 1) * 0.46), 2);
                    var bar = new System.Windows.Shapes.Rectangle { Width = 3, Height = height, RadiusX = 1.5, RadiusY = 1.5, Fill = i % 4 == 0 ? gold : new SolidColorBrush(Color.FromRgb(91, 90, 79)), RenderTransformOrigin = new Point(0.5, 0.5) };
                    Canvas.SetLeft(bar, 28 + i * 12); Canvas.SetTop(bar, 150 - height / 2);
                    var scale = new ScaleTransform(1, 0.65); bar.RenderTransform = scale;
                    Animate(scale, ScaleTransform.ScaleYProperty, new DoubleAnimation(0.3, 1, TimeSpan.FromMilliseconds(950 + i * 73)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut }, BeginTime = TimeSpan.FromMilliseconds(i * 45) });
                    canvas.Children.Add(bar);
                }
            } else if (rings) {
                for (int i = 0; i < 5; i++) {
                    double size = 70 + i * 38;
                    var ring = new System.Windows.Shapes.Ellipse { Width = size, Height = size, StrokeThickness = i == 0 ? 1.8 : 1, Stroke = i % 2 == 0 ? gold : muted, Opacity = 0.65 - i * 0.1, RenderTransformOrigin = new Point(0.5, 0.5) };
                    Canvas.SetLeft(ring, (300 - size) / 2); Canvas.SetTop(ring, (300 - size) / 2);
                    var transform = new ScaleTransform(1, 1); ring.RenderTransform = transform;
                    var pulse = new DoubleAnimation(0.83, 1.05, TimeSpan.FromMilliseconds(2000 + i * 160)) { AutoReverse = true, RepeatBehavior = stage == 8 ? new RepeatBehavior(1) : RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } };
                    Animate(transform, ScaleTransform.ScaleXProperty, pulse); Animate(transform, ScaleTransform.ScaleYProperty, pulse);
                    canvas.Children.Add(ring);
                }
            } else {
                string[] colors = { "#4B463A", "#3D4246", "#8A7A58", "#4B5054", "#A18C62", "#4B5054", "#C0A777", "#52585D" };
                for (int i = 0; i < 8; i++) {
                    double x = 18 + i * 39;
                    var segment = new BezierSegment(new Point(145 + i * 2, 110), new Point(50 + i * 22, 188), new Point(x, 288), true);
                    var figure = new PathFigure { StartPoint = new Point(x, 12), IsClosed = false }; figure.Segments.Add(segment);
                    var geometry = new PathGeometry(); geometry.Figures.Add(figure);
                    var path = new System.Windows.Shapes.Path { Stroke = (Brush)new BrushConverter().ConvertFromString(colors[i]), StrokeThickness = 1.8, Data = geometry };
                    canvas.Children.Add(path);
                    var duration = TimeSpan.FromMilliseconds((stage == 6 ? 2200 : 3600) + i * 140);
                    Animate(segment, BezierSegment.Point1Property, new PointAnimation(new Point(105 + i * 7, 87), new Point(180 - i * 2, 131), duration) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } });
                    Animate(segment, BezierSegment.Point2Property, new PointAnimation(new Point(74 + i * 18, 210), new Point(100 + i * 16, 168), duration) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut }, BeginTime = TimeSpan.FromMilliseconds(i * 100) });
                }
            }
            var orbit = new System.Windows.Shapes.Ellipse { Width = 106, Height = 106, Stroke = gold, StrokeThickness = 1, StrokeDashArray = new DoubleCollection { 3, 164 }, RenderTransformOrigin = new Point(0.5, 0.5) };
            brandOrbit = new RotateTransform(); orbit.RenderTransform = brandOrbit; Canvas.SetLeft(orbit, 97); Canvas.SetTop(orbit, 97); canvas.Children.Add(orbit);
            brandNote = new System.Windows.Shapes.Ellipse { Width = 14, Height = 14, Fill = gold }; Canvas.SetLeft(brandNote, 143); Canvas.SetTop(brandNote, 143); canvas.Children.Add(brandNote);
            if (SystemParameters.ClientAreaAnimation) canvas.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.5, 1, TimeSpan.FromMilliseconds(280)));
            Find<TextBlock>("BrandMessage").Text = BrandMessages()[0];
            brandTimer.Stop(); if (stage > 1 && stage < 8) brandTimer.Start();
        }
        private string[] BrandMessages()
        {
            if (stage == 8 && terminalResult == "cancelled") return new[] { "A operação foi interrompida.\nSeus projetos permanecem." };
            if (stage == 8 && terminalResult != "success") return new[] { "A sua música permanece.\nOs seus projetos também." };
            if (remove) return new[] { "A sua música permanece.\nOs seus projetos também.", "Só os arquivos do aplicativo\nserão removidos.", "Um novo começo pode\nacontecer a qualquer momento." };
            if (stage == 3) return new[] { "Antes da primeira nota,\ntudo em harmonia.", "Conferindo o espaço\npara a sua próxima ideia." };
            if (stage == 4) return new[] { "O seu espaço.\nO seu ritmo.", "Cada escolha prepara\no seu próximo movimento." };
            if (stage == 5) return new[] { "Organizando cada detalhe\nda sua experiência.", "Conferindo a integridade\nantes de começar." };
            if (stage == 6) return new[] { operationMode == "update" ? "Novas possibilidades.\nA mesma identidade." : operationMode == "repair" ? "Recompondo os detalhes.\nPreservando a sua música." : "Uma nova experiência musical\nestá tomando forma.", "Java, Chromium e Audiveris.\nTodos incluídos no pacote.", "Seus projetos e preferências\ncontinuam neste computador." };
            if (stage == 7) return new[] { "Conferindo cada arquivo.\nCuidando de cada detalhe.", "Uma última conferência\nantes do próximo movimento." };
            if (stage == 8) return new[] { "Tudo pronto para o seu\npróximo movimento." };
            return new[] { "Uma nova experiência musical\ncomeça aqui.", "Partituras, edição e estudo.\nNo seu próprio ritmo.", "Um espaço para explorar,\nouvir e aprender." };
        }
        private void RotateBrandMessage()
        {
            var messages = BrandMessages(); brandMessageIndex = (brandMessageIndex + 1) % messages.Length;
            var label = Find<TextBlock>("BrandMessage"); label.Text = messages[brandMessageIndex];
            if (SystemParameters.ClientAreaAnimation) label.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.55, 1, TimeSpan.FromMilliseconds(260)));
        }
        private void Welcome()
        {
            busy = false; remove = false; terminalResult = ""; engine.Removing = false; engine.CancelRequested = false; engine.DetectPrevious();
            bool installed = engine.Installed;
            bool update = engine.IsUpdate;
            operationMode = DetectMode();
            Find<TextBlock>("ShellMode").Text = update ? "Atualizador" : installed ? "Manutenção" : "Instalador";
            SetStage(2, update ? "Um novo movimento." : installed ? "Seu Arpeon, em dia." : "Dê espaço à sua música.", update ? "Atualize da versão " + engine.ExistingVersion + " para " + engine.Info.Version + ". Seus projetos e preferências serão preservados." : installed ? "Abra o aplicativo ou cuide da instalação. Seus projetos e preferências serão preservados." : "Leia, edite e estude suas partituras. Seu próximo momento musical começa no Arpeon.");
            var intro = new StackPanel { Margin = new Thickness(0, 12, 0, 0) };
            intro.Children.Add(Text("Tudo o que você precisa, aqui.", 17, Brushes.White));
            intro.Children.Add(Text("Java, Chromium e reconhecimento de partituras incluídos no pacote. A instalação funciona sem download adicional.", 13, muted));
            intro.Children.Add(new Border { Height = 1, Background = new SolidColorBrush(Color.FromRgb(48, 52, 56)), Margin = new Thickness(0, 24, 0, 17) });
            intro.Children.Add(Text("Para este usuário · Windows x64", 12, gold));
            intro.Children.Add(Text("Versão " + engine.Info.Version + " · " + InstallerEngine.Size(engine.Info.InstalledSize) + " no disco", 12, muted));
            content.Children.Add(intro);
            SetNext(update ? "Atualizar Arpeon" : operationMode == "repair" ? "Reparar arquivos" : "Começar instalação", delegate { RunChecks(); });
            if (installed) {
                secondary.Children.Add(Button("Abrir Arpeon", Launch));
                secondary.Children.Add(Button("Desinstalar", ConfirmRemoval));
            } else secondary.Children.Add(Button("Sair", RequestClose));
            next.Focus();
        }
        private async void RunChecks()
        {
            engine.Removing = remove;
            operationMode = DetectMode(); terminalResult = "";
            busy = true; engine.CancelRequested = false;
            SetStage(3, "Vamos conferir seu sistema.", remove ? "Conferindo a instalação existente antes de remover o aplicativo." : operationMode == "update" ? "Verificações reais antes de atualizar o Arpeon." : operationMode == "repair" ? "Conferindo o ambiente para recuperar sua instalação." : "Verificações reais para uma instalação tranquila."); SetNext("Verificando…", null);
            secondary.Children.Add(Button("Cancelar", RequestClose));
            var checks = new[] {
                new Tuple<string, Func<string>>("Sistema operacional", engine.CheckOperatingSystem),
                new Tuple<string, Func<string>>("Arquitetura", engine.CheckArchitecture),
                new Tuple<string, Func<string>>("Instalações anteriores", engine.CheckPrevious),
                new Tuple<string, Func<string>>("Espaço em disco", engine.CheckSpace),
                new Tuple<string, Func<string>>("Permissões", engine.CheckPermissions),
                new Tuple<string, Func<string>>("Componentes e runtimes", engine.CheckDependencies)
            };
            var rows = new StackPanel[checks.Length];
            for (int i = 0; i < checks.Length; i++) {
                rows[i] = new StackPanel { Margin = new Thickness(0, 0, 0, 12) };
                rows[i].Children.Add(Text(checks[i].Item1, 13, muted));
                rows[i].Children.Add(Text("Aguardando", 11, muted)); content.Children.Add(rows[i]);
            }
            try {
                for (int i = 0; i < checks.Length; i++) {
                    if (engine.CancelRequested) throw new OperationCanceledException();
                    ((TextBlock)rows[i].Children[1]).Text = "Verificando…";
                    string result;
                    try { result = await Task.Run(checks[i].Item2); }
                    catch { ((TextBlock)rows[i].Children[0]).Foreground = error; ((TextBlock)rows[i].Children[1]).Text = "Requer sua atenção"; throw; }
                    ((TextBlock)rows[i].Children[0]).Foreground = Brushes.White;
                    ((TextBlock)rows[i].Children[1]).Foreground = gold;
                    ((TextBlock)rows[i].Children[1]).Text = result;
                    engine.Log(checks[i].Item1 + ": " + result);
                }
                busy = false;
                if (remove) StartInstall(); else Options();
            } catch (OperationCanceledException) { Cancelled(); }
            catch (Exception ex) { Failure(ex, delegate { RunChecks(); }, false); }
        }
        private void Options()
        {
            SetStage(4, "Do seu jeito.", engine.ExistingVersion.Length == 0 ? "As opções recomendadas já estão prontas. Ajuste apenas o que precisar." : "A pasta e as preferências anteriores foram recuperadas para preservar a instalação.");
            content.Children.Add(Text("Pasta do aplicativo", 12, muted));
            directory = new TextBox { Text = engine.Target, Margin = new Thickness(0, 8, 0, 8), IsReadOnly = engine.ExistingVersion.Length != 0 };
            System.Windows.Automation.AutomationProperties.SetName(directory, "Pasta do aplicativo"); content.Children.Add(directory);
            if (!directory.IsReadOnly) content.Children.Add(Button("Escolher outra pasta…", Browse));
            var capacity = Text("", 11, muted);
            Action updateCapacity = delegate {
                try {
                    if (!Path.IsPathRooted(directory.Text)) throw new IOException();
                    capacity.Text = InstallerEngine.Size(engine.Info.InstalledSize) + " necessários · " + InstallerEngine.Size(engine.AvailableSpace(directory.Text)) + " disponíveis";
                    capacity.Foreground = muted;
                } catch (Exception ex) {
                    if (!(ex is IOException || ex is ArgumentException || ex is UnauthorizedAccessException || ex is NotSupportedException)) throw;
                    capacity.Text = "Confira o caminho. Não foi possível consultar o espaço desta unidade."; capacity.Foreground = error;
                }
            };
            directory.TextChanged += delegate { updateCapacity(); }; updateCapacity(); content.Children.Add(capacity);
            content.Children.Add(new Border { Height = 1, Background = new SolidColorBrush(Color.FromRgb(48, 52, 56)), Margin = new Thickness(0, 19, 0, 12) });
            desktop = new CheckBox { Content = "Atalho na área de trabalho", IsChecked = engine.Desktop, IsEnabled = !engine.Installed };
            menu = new CheckBox { Content = "Atalho no menu Iniciar", IsChecked = engine.Menu, IsEnabled = !engine.Installed };
            content.Children.Add(desktop); content.Children.Add(menu);
            content.Children.Add(Text("Idioma do instalador: Português (Brasil)", 11, muted));
            SetNext(operationMode == "repair" ? "Reparar instalação" : operationMode == "update" ? "Atualizar Arpeon" : "Instalar Arpeon", delegate {
                engine.Target = directory.Text; engine.Desktop = desktop.IsChecked == true; engine.Menu = menu.IsChecked == true;
                StartInstall();
            });
            secondary.Children.Add(Button("Voltar", Welcome));
        }
        private void Browse()
        {
            using (var dialog = new System.Windows.Forms.FolderBrowserDialog { Description = "Escolha a pasta do aplicativo Arpeon", SelectedPath = directory.Text, ShowNewFolderButton = true })
                if (dialog.ShowDialog(new NativeOwner(window)) == System.Windows.Forms.DialogResult.OK) directory.Text = dialog.SelectedPath;
        }
        private void ProgressContent(string label)
        {
            var block = new StackPanel { Margin = new Thickness(0, 42, 0, 0) };
            percent = new TextBlock { Text = "Em andamento", FontSize = 30, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 12) };
            progressBar = new ProgressBar { Minimum = 0, Maximum = 100, Value = 0 };
            System.Windows.Automation.AutomationProperties.SetName(progressBar, "Progresso da fase atual");
            operation = Text(label, 13, gold); operation.Margin = new Thickness(0, 17, 0, 5);
            detail = Text("", 12, muted);
            block.Children.Add(percent); block.Children.Add(progressBar); block.Children.Add(operation); block.Children.Add(detail); content.Children.Add(block);
            StartMotion();
        }
        private async void StartInstall()
        {
            busy = true; engine.CancelRequested = false; engine.Committed = false;
            engine.Removing = remove;
            operationMode = DetectMode(); terminalResult = "";
            SetStage(5, remove ? "Preparando a despedida." : engine.IsUpdate ? "Preparando sua atualização." : "Cada detalhe, no lugar.", "Preparando o pacote local e conferindo sua integridade antes de alterar o aplicativo.");
            ProgressContent("Preparando arquivos"); SetNext("Preparando…", null); secondary.Children.Add(Button("Cancelar", RequestClose));
            try {
                await Task.Run((Action)engine.Prepare);
                SetStage(6, remove ? "Desinstalando o Arpeon" : operationMode == "update" ? "Atualizando o Arpeon." : operationMode == "repair" ? "Reparando o Arpeon." : "Sua música está chegando.", "Acompanhe as operações do Windows Installer. O progresso corresponde à fase em execução.");
                ProgressContent("Preparando componentes"); SetNext(remove ? "Desinstalando…" : operationMode == "update" ? "Atualizando…" : operationMode == "repair" ? "Reparando…" : "Instalando…", null); secondary.Children.Add(Button("Cancelar", RequestClose));
                uint code = await Task.Run(delegate { return engine.Install(remove); });
                StopMotion();
                if (code == 1602) { Cancelled(); return; }
                if (code != 0 && code != 3010) throw new IOException(ErrorMessage(code) + (engine.LastError.Length == 0 ? "" : "\n" + engine.LastError));
                engine.CancelRequested = false;
                if (remove) {
                    if (engine.Installed) throw new IOException("A remoção não foi confirmada pelo Windows.");
                    engine.RemoveMaintenanceCache();
                    busy = false; terminalResult = "removed"; SetStage(8, "Até a próxima música.", "O aplicativo foi removido. Seus projetos e preferências continuam neste computador.");
                    content.Children.Add(Text("Remoção concluída", 18, gold)); SetNext("Concluir", Close); return;
                }
                await Validate(code == 3010);
            } catch (OperationCanceledException) { Cancelled(); }
            catch (Exception ex) { Failure(ex, delegate { StartInstall(); }, engine.Committed && !remove); }
            finally { engine.Dispose(); }
        }
        private async Task Validate(bool restart)
        {
            busy = true; engine.CanCancel = false;
            SetStage(7, "Uma última conferência.", "Validando os arquivos, os componentes incluídos, o registro no Windows e os atalhos escolhidos.");
            ProgressContent("Conferindo a instalação"); SetNext("Validando…", null);
            try { await Task.Run(delegate { engine.RegisterMaintenance(); engine.ValidateInstallation(); engine.CleanupObsoleteMaintenance(); }); validated = true; Complete(restart); }
            catch (Exception ex) { Failure(ex, async delegate { await Validate(restart); }, true); }
            finally { engine.CanCancel = true; }
        }
        private void Complete(bool restart)
        {
            busy = false; terminalResult = "success"; StopMotion();
            SetStage(8, "Arpeon está pronto.", restart ? "A instalação foi validada. Reinicie o Windows antes de abrir o aplicativo." : "Sua próxima ideia musical já tem onde começar.");
            Find<TextBlock>("BrandMessage").Text = "Tudo pronto para o seu\npróximo movimento.";
            var check = new System.Windows.Shapes.Path { Stroke = gold, StrokeThickness = 2.5, Data = Geometry.Parse("M2 14 L11 23 L31 3"), Width = 36, Height = 32, HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 20, 0, 18) };
            content.Children.Add(check); content.Children.Add(Text("Instalação verificada", 18, Brushes.White));
            content.Children.Add(Text("Versão " + engine.Info.Version + " · " + engine.Inventory.Length + " arquivos conferidos", 13, muted));
            content.Children.Add(Text(engine.Target, 12, muted));
            launch = new CheckBox { Content = "Iniciar o Arpeon ao concluir", IsChecked = !restart, IsEnabled = !restart, Margin = new Thickness(0, 26, 0, 0) }; content.Children.Add(launch);
            SetNext(restart ? "Concluir" : "Abrir Arpeon", restart ? (Action)Close : LaunchAndClose);
            secondary.Children.Add(Button("Concluir", delegate { if (launch.IsChecked == true) LaunchAndClose(); else Close(); }));
            if (SystemParameters.ClientAreaAnimation) check.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(240)));
        }
        private static string ErrorMessage(uint code)
        {
            switch (code) {
                case 1618: return "Há outra instalação em andamento. Aguarde sua conclusão e tente novamente.";
                case 1638: return "Esta versão já está registrada a partir de outro pacote. Use a manutenção do aplicativo em Configurações do Windows.";
                case 1625: return "Uma política do Windows bloqueou a instalação. Consulte o administrador deste computador.";
                case 1622: return "O Windows não conseguiu gravar o registro técnico. Confira as permissões da pasta temporária e tente novamente.";
                case 1632: return "O Windows não conseguiu usar a pasta temporária. Confira espaço e permissões.";
                default: return "O Windows Installer não concluiu a operação (código " + code + "). Consulte o registro e tente novamente após corrigir a causa.";
            }
        }
        private void Failure(Exception ex, Action retry, bool committed)
        {
            busy = false; StopMotion(); engine.Log(ex.ToString());
            if (percent != null) percent.Text = "Requer atenção";
            SetNext(committed ? "Verificar novamente" : "Tentar novamente", retry);
            secondary.Children.Clear(); secondary.Children.Add(Button(committed ? "Reparar arquivos" : "Voltar", committed ? (Action)Welcome : OptionsOrWelcome));
            Notice(committed ? "Instalado, mas ainda não validado" : "Não foi possível continuar", ex.Message);
        }
        private void OptionsOrWelcome() { if (remove) RemovalWelcome(); else if (stage >= 4) Options(); else Welcome(); }
        private void Cancelled()
        {
            busy = false; terminalResult = "cancelled"; StopMotion(); engine.Log("Operation cancelled by user; MSI returned before closing UI.");
            SetStage(8, remove ? "Desinstalação interrompida." : operationMode == "update" ? "Atualização interrompida." : operationMode == "repair" ? "Reparação interrompida." : "Instalação interrompida.", "A operação foi encerrada. Consulte o registro se precisar conferir a recuperação realizada pelo Windows Installer.");
            content.Children.Add(Text("Seus projetos e preferências foram preservados.", 15, gold));
            SetNext(remove ? "Voltar à manutenção" : "Tentar novamente", Welcome); secondary.Children.Add(Button("Fechar", Close));
        }
        private void ConfirmRemoval()
        {
            Notice("Remover o Arpeon?", "Os arquivos do aplicativo serão removidos. Projetos e preferências pessoais serão preservados.");
            var actions = Find<StackPanel>("NoticeActions");
            actions.Children.Add(Button("Manter instalado", HideNotice));
            actions.Children.Add(Button("Confirmar remoção", delegate { remove = true; Find<TextBlock>("ShellMode").Text = "Desinstalador"; RunChecks(); }));
        }
        private void RemovalWelcome()
        {
            engine.DetectPrevious(); remove = true; busy = false; operationMode = "remove"; terminalResult = "";
            Find<TextBlock>("ShellMode").Text = "Desinstalador";
            if (!engine.Installed) {
                engine.RemoveMaintenanceCache(); terminalResult = "absent";
                SetStage(8, "Esta versão não está instalada.", "A versão " + engine.Info.Version + " não está registrada. Para remover outra versão, use a manutenção correspondente em Aplicativos do Windows.");
                SetNext("Concluir", Close); return;
            }
            SetStage(2, "Sua música fica com você.", "Desinstalar remove o aplicativo e seus atalhos. Seus projetos, histórico e preferências continuam guardados neste computador.");
            content.Children.Add(Text("Arpeon " + engine.ExistingVersion, 18, Brushes.White));
            content.Children.Add(Text(engine.Target, 12, muted));
            content.Children.Add(Text("Você pode instalar o Arpeon novamente quando quiser.", 13, muted));
            SetNext("Desinstalar Arpeon", ConfirmRemoval);
            secondary.Children.Add(Button("Manter instalado", Close));
        }
        private string DetectMode() { return remove ? "remove" : engine.IsUpdate ? "update" : engine.Installed || engine.ExistingVersion == engine.Info.Version ? "repair" : "install"; }
        private void RequestClose()
        {
            if (!busy) { Close(); return; }
            if (!engine.CanCancel || stage == 7) {
                Notice("Concluindo uma operação protegida", "Aguarde esta etapa terminar antes de fechar o instalador."); return;
            }
            Notice(remove ? "Interromper a desinstalação?" : operationMode == "update" ? "Interromper a atualização?" : operationMode == "repair" ? "Interromper a reparação?" : "Interromper a instalação?", "O instalador solicitará o cancelamento e aguardará a recuperação do Windows antes de encerrar.");
            var actions = Find<StackPanel>("NoticeActions");
            actions.Children.Add(Button(remove ? "Continuar desinstalação" : operationMode == "update" ? "Continuar atualização" : operationMode == "repair" ? "Continuar reparação" : "Continuar instalação", HideNotice));
            actions.Children.Add(Button("Interromper", delegate {
                engine.CancelRequested = true; HideNotice(); SetNext("Cancelando…", null);
                if (operation != null) operation.Text = "Aguardando encerramento seguro";
            }));
        }
        private void Notice(string title, string body)
        {
            Find<TextBlock>("NoticeTitle").Text = title; Find<TextBlock>("NoticeBody").Text = body;
            Find<StackPanel>("NoticeActions").Children.Clear(); Find<Border>("Notification").Visibility = Visibility.Visible;
            window.Dispatcher.BeginInvoke(DispatcherPriority.ContextIdle, new Action(delegate {
                if (Find<Border>("Notification").Visibility != Visibility.Visible) return;
                var titleLabel = Find<TextBlock>("NoticeTitle"); Announce(titleLabel);
                var actions = Find<StackPanel>("NoticeActions");
                if (actions.Children.Count > 0) ((Button)actions.Children[0]).Focus(); else titleLabel.Focus();
            }));
        }
        private static void Announce(FrameworkElement element)
        {
            var peer = FrameworkElementAutomationPeer.FromElement(element) ?? FrameworkElementAutomationPeer.CreatePeerForElement(element);
            if (peer != null) peer.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
        }
        private void HideNotice()
        {
            Find<Border>("Notification").Visibility = Visibility.Collapsed;
            if (next.IsEnabled) next.Focus(); else Find<TextBlock>("Heading").Focus();
        }
        private void QueueProgress(string phase, int? value, string text)
        {
            lock (progressLock) { pendingPhase = phase; pendingPercent = value; pendingDetail = text; pendingStage = stage; }
            if (Interlocked.Exchange(ref progressScheduled, 1) != 0) return;
            window.Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(delegate {
                lock (progressLock) {
                    Interlocked.Exchange(ref progressScheduled, 0);
                    if (!busy || percent == null || stage < 5 || stage > 7 || pendingStage != stage) return;
                    percent.Text = pendingPercent.HasValue ? pendingPercent + "%" : "Em andamento";
                    operation.Text = pendingPhase; detail.Text = pendingDetail;
                    if (SystemParameters.ClientAreaAnimation && pendingPercent.HasValue) {
                        progressBar.BeginAnimation(System.Windows.Controls.Primitives.RangeBase.ValueProperty,
                            new DoubleAnimation(progressBar.Value, pendingPercent.Value, TimeSpan.FromMilliseconds(140)) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
                    } else {
                        progressBar.BeginAnimation(System.Windows.Controls.Primitives.RangeBase.ValueProperty, null);
                        progressBar.Value = pendingPercent ?? 0;
                    }
                    if (pendingPercent.HasValue && brandOrbit != null) brandOrbit.Angle = pendingPercent.Value * 3.6;
                }
            }));
        }
        private void StartMotion()
        {
            if (SystemParameters.ClientAreaAnimation && !SystemParameters.HighContrast)
                brandNote.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0.45, 1, TimeSpan.FromMilliseconds(950)) { AutoReverse = true, RepeatBehavior = RepeatBehavior.Forever });
        }
        private void StopMotion() { if (brandNote != null) brandNote.BeginAnimation(UIElement.OpacityProperty, null); }
        private void Launch()
        {
            try { engine.Launch(); } catch (Exception ex) { Notice("Não foi possível abrir o Arpeon", ex.Message); }
        }
        private void LaunchAndClose()
        {
            try { if (!validated) throw new InvalidOperationException("A instalação ainda não foi validada."); engine.Launch(); Close(); }
            catch (Exception ex) { Notice("Não foi possível abrir o Arpeon", ex.Message); }
        }
        private void ShowLogs()
        {
            try { Process.Start(new ProcessStartInfo("notepad.exe", "\"" + (engine.MsiLog != null && File.Exists(engine.MsiLog) ? engine.MsiLog : engine.SessionLog) + "\"") { UseShellExecute = true }); }
            catch (Exception ex) { Notice("Registro da instalação", engine.SessionLog + "\n" + ex.Message); }
        }
        private void Close() { closing = true; window.Close(); }
        private sealed class NativeOwner : System.Windows.Forms.IWin32Window
        {
            private readonly Window owner;
            internal NativeOwner(Window value) { owner = value; }
            public IntPtr Handle { get { return new System.Windows.Interop.WindowInteropHelper(owner).Handle; } }
        }
    }

    internal static class Program
    {
        [STAThread]
        public static int Main(string[] args)
        {
            bool uninstall = Array.IndexOf(args, "--uninstall") >= 0;
            bool quiet = Array.IndexOf(args, "--quiet") >= 0;
            bool hasPayload;
            using (var payload = Assembly.GetExecutingAssembly().GetManifestResourceStream("payload.msi")) hasPayload = payload != null;
            // Run the small cached maintenance executable from TEMP so removal can delete its cache.
            if (!hasPayload && Array.IndexOf(args, "--host") < 0) {
                try {
                    var info = new System.Web.Script.Serialization.JavaScriptSerializer().Deserialize<BundleInfo>(InstallerEngine.ResourceText("bundle.json"));
                    string host = Path.Combine(Path.GetTempPath(), "ArpeonMaintenance-" + info.ProductCode.Trim('{', '}') + ".exe");
                    File.Copy(Assembly.GetExecutingAssembly().Location, host, true);
                    var child = Process.Start(new ProcessStartInfo(host, "--host" + (uninstall ? " --uninstall" : "") + (quiet ? " --quiet" : "")) { UseShellExecute = false });
                    if (quiet) { using (child) { child.WaitForExit(); return child.ExitCode; } }
                    return 0;
                } catch (Exception ex) { if (!quiet) MessageBox.Show("Não foi possível abrir a manutenção. Execute o Setup completo.\n" + ex.Message, "ARPEON"); return 1603; }
            }
            bool created;
            using (var mutex = new Mutex(true, @"Local\ARPEON-Setup", out created)) {
                if (!created) { if (!quiet) MessageBox.Show("O instalador do Arpeon já está aberto.", "ARPEON"); return 1618; }
                try {
                    using (var engine = new InstallerEngine()) {
                        if (quiet && uninstall) {
                            if (!engine.Installed) { engine.RemoveMaintenanceCache(); return 0; }
                            engine.Removing = true; engine.Prepare(); uint result = engine.Install(true);
                            if (result == 0 || result == 3010) engine.RemoveMaintenanceCache();
                            return (int)result;
                        }
                        var app = new Application();
                        app.DispatcherUnhandledException += delegate(object sender, DispatcherUnhandledExceptionEventArgs failure) {
                            engine.Log(failure.Exception.ToString());
                            MessageBox.Show("O instalador encontrou uma falha. Consulte o registro em:\n" + engine.SessionLog, "ARPEON", MessageBoxButton.OK, MessageBoxImage.Error);
                            failure.Handled = true;
                        };
                        return app.Run(new InstallerWindow(engine, uninstall).Window);
                    }
                } catch (Exception ex) { if (!quiet) MessageBox.Show("Não foi possível iniciar o instalador.\n" + ex.Message, "ARPEON", MessageBoxButton.OK, MessageBoxImage.Error); return 1603; }
            }
        }
    }
}
