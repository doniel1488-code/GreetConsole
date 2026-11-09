using System.Drawing;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;

namespace GreetConsole;

internal sealed class MainForm : Form
{
    private const int MaxHistory = 5_000;
    private const int InitialTailBytes = 1_500_000;
    private const int MaxPendingLineBytes = 2_000_000;

    private static readonly Color WindowBackground = Color.FromArgb(12, 16, 25);
    private static readonly Color PanelBackground = Color.FromArgb(20, 27, 40);
    private static readonly Color DefaultText = Color.FromArgb(208, 218, 235);
    private static readonly Color MutedText = Color.FromArgb(139, 153, 177);
    private static readonly Color Accent = Color.FromArgb(158, 176, 255);
    private static Color Rgb(int hex) => Color.FromArgb((hex >> 16) & 255, (hex >> 8) & 255, hex & 255);

    private static readonly Color[] MinecraftColors =
    [
        Rgb(0x000000), Rgb(0x0000AA), Rgb(0x00AA00), Rgb(0x00AAAA),
        Rgb(0xAA0000), Rgb(0xAA00AA), Rgb(0xFFAA00), Rgb(0xAAAAAA),
        Rgb(0x555555), Rgb(0x5555FF), Rgb(0x55FF55), Rgb(0x55FFFF),
        Rgb(0xFF5555), Rgb(0xFF55FF), Rgb(0xFFFF55), Rgb(0xFFFFFF)
    ];

    private readonly string _logPath;
    private readonly List<string> _history = [];
    private readonly List<byte> _pendingBytes = [];
    private readonly System.Windows.Forms.Timer _pollTimer = new() { Interval = 200 };
    private readonly RichTextBox _logView = new();
    private readonly TextBox _searchBox = new();
    private readonly ComboBox _levelFilter = new();
    private readonly Label _statusLabel = new();
    private readonly CheckBox _autoScroll = new() { Text = "Автопрокрутка", Checked = true, AutoSize = true };
    private readonly Button _pauseButton = new() { Text = "Пауза", AutoSize = true };
    private readonly TextBox _commandInput = new();
    private readonly Button _sendCommandButton = new() { Text = "Отправить", AutoSize = true, Enabled = false };
    private readonly Label _commandStatusLabel = new();
    private readonly Label _connectionIndicator = new();
    private readonly int _commandPort;
    private readonly string _commandToken;
    private readonly object _commandWriteLock = new();
    private TcpClient? _commandClient;
    private StreamWriter? _commandWriter;
    private long _position;
    private bool _paused;
    private string _monoFont = "Consolas";

    public MainForm(string logPath, int commandPort, string commandToken)
    {
        _logPath = Path.GetFullPath(logPath);
        _commandPort = commandPort;
        _commandToken = commandToken;
        Text = "GreetConsole — Журнал Minecraft";
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(760, 460);
        Size = new Size(1160, 760);
        BackColor = WindowBackground;
        ForeColor = DefaultText;
        Font = new Font("Segoe UI", 9F);
        BuildInterface();
        LoadInitialTail();
        _pollTimer.Tick += (_, _) => PollLog();
        _pollTimer.Start();
        FormClosed += (_, _) =>
        {
            _pollTimer.Stop();
            _commandClient?.Close();
        };
        Shown += async (_, _) => await ConnectCommandBridgeAsync();
    }

    private void BuildInterface()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = WindowBackground,
            Padding = new Padding(16),
            ColumnCount = 1,
            RowCount = 5,
            Margin = Padding.Empty
        };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(root);

        var header = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            BackColor = WindowBackground,
            Margin = new Padding(0, 0, 0, 14)
        };
        header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        header.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));

        var heading = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            BackColor = WindowBackground,
            Margin = Padding.Empty
        };
        heading.Controls.Add(new Label
        {
            Text = "GREETCONSOLE",
            ForeColor = Accent,
            Font = new Font("Segoe UI Semibold", 16F),
            AutoSize = true,
            Margin = Padding.Empty
        });
        heading.Controls.Add(new Label
        {
            Text = "ЖУРНАЛ MINECRAFT  /  УПРАВЛЕНИЕ ИГРОКОМ",
            ForeColor = MutedText,
            Font = new Font("Segoe UI", 8.5F),
            AutoSize = true,
            Margin = new Padding(1, 1, 0, 0)
        });
        header.Controls.Add(heading, 0, 0);

        _connectionIndicator.Text = "●  ОЖИДАНИЕ ИГРЫ";
        _connectionIndicator.AutoSize = true;
        _connectionIndicator.ForeColor = MutedText;
        _connectionIndicator.Font = new Font("Segoe UI Semibold", 8.5F);
        _connectionIndicator.Margin = new Padding(12, 12, 2, 0);
        header.Controls.Add(_connectionIndicator, 1, 0);
        root.Controls.Add(header, 0, 0);

        var toolbar = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            BackColor = PanelBackground,
            Padding = new Padding(10, 8, 10, 8),
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0, 0, 0, 10)
        };
        toolbar.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        toolbar.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        var actions = new FlowLayoutPanel
        {
            AutoSize = true,
            WrapContents = false,
            BackColor = PanelBackground,
            Margin = Padding.Empty
        };
        StyleButton(_pauseButton);
        _pauseButton.Click += (_, _) =>
        {
            _paused = !_paused;
            _pauseButton.Text = _paused ? "Продолжить" : "Пауза";
            if (!_paused) RebuildLog();
            UpdateStatus();
        };
        actions.Controls.Add(_pauseButton);

        var clearButton = MakeButton("Очистить");
        clearButton.Click += (_, _) => { _history.Clear(); _pendingBytes.Clear(); _logView.Clear(); UpdateStatus(); };
        actions.Controls.Add(clearButton);

        var copyButton = MakeButton("Копировать");
        copyButton.Click += (_, _) =>
        {
            if (_logView.TextLength > 0)
                Clipboard.SetText(_logView.SelectedText.Length > 0 ? _logView.SelectedText : _logView.Text);
        };
        actions.Controls.Add(copyButton);
        toolbar.Controls.Add(actions, 0, 0);

        var filters = new FlowLayoutPanel
        {
            AutoSize = true,
            WrapContents = true,
            FlowDirection = FlowDirection.RightToLeft,
            BackColor = PanelBackground,
            Margin = new Padding(12, 0, 0, 0),
            Anchor = AnchorStyles.Right
        };
        _levelFilter.DropDownStyle = ComboBoxStyle.DropDownList;
        _levelFilter.Width = 125;
        _levelFilter.Items.AddRange(["Все уровни", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OTHER"]);
        _levelFilter.SelectedIndex = 0;
        _levelFilter.SelectedIndexChanged += (_, _) => RebuildLog();
        filters.Controls.Add(_levelFilter);

        _searchBox.Width = 220;
        _searchBox.BackColor = WindowBackground;
        _searchBox.ForeColor = DefaultText;
        _searchBox.BorderStyle = BorderStyle.FixedSingle;
        _searchBox.Margin = new Padding(0, 3, 8, 0);
        _searchBox.PlaceholderText = "Поиск по журналу";
        _searchBox.TextChanged += (_, _) => HighlightSearch();
        filters.Controls.Add(_searchBox);

        var searchLabel = new Label
        {
            Text = "ПОИСК",
            AutoSize = true,
            ForeColor = MutedText,
            Font = new Font("Segoe UI Semibold", 8F),
            Margin = new Padding(0, 8, 8, 0)
        };
        filters.Controls.Add(searchLabel);

        _autoScroll.Text = "Автопрокрутка";
        _autoScroll.ForeColor = DefaultText;
        _autoScroll.BackColor = PanelBackground;
        _autoScroll.Margin = new Padding(10, 5, 4, 0);
        filters.Controls.Add(_autoScroll);
        toolbar.Controls.Add(filters, 1, 0);
        root.Controls.Add(toolbar, 0, 1);

        _logView.Dock = DockStyle.Fill;
        _logView.ReadOnly = true;
        _logView.BackColor = WindowBackground;
        _logView.ForeColor = DefaultText;
        _logView.BorderStyle = BorderStyle.FixedSingle;
        _logView.WordWrap = false;
        _logView.HideSelection = false;
        _logView.DetectUrls = false;
        _logView.Font = new Font(_monoFont, 9.5F);
        _logView.Margin = new Padding(0);
        root.Controls.Add(_logView, 0, 2);

        var commandPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            BackColor = PanelBackground,
            Padding = new Padding(12, 10, 12, 8),
            ColumnCount = 1,
            RowCount = 3,
            Margin = new Padding(0, 12, 0, 8)
        };
        commandPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        commandPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        commandPanel.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var commandHeading = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = new Padding(0, 0, 0, 8)
        };
        commandHeading.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        commandHeading.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        commandHeading.Controls.Add(new Label
        {
            Text = "КОМАНДНАЯ СТРОКА",
            ForeColor = Accent,
            Font = new Font("Segoe UI Semibold", 9F),
            AutoSize = true,
            Margin = Padding.Empty
        }, 0, 0);
        commandHeading.Controls.Add(new Label
        {
            Text = "ВВОД ОТ ИМЕНИ ИГРОКА",
            ForeColor = MutedText,
            Font = new Font("Segoe UI", 8F),
            AutoSize = true,
            Margin = new Padding(8, 1, 0, 0)
        }, 1, 0);
        commandPanel.Controls.Add(commandHeading, 0, 0);

        var inputRow = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 40,
            ColumnCount = 3,
            RowCount = 1,
            Margin = new Padding(0, 0, 0, 7)
        };
        inputRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 34));
        inputRow.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        inputRow.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 126));

        inputRow.Controls.Add(new Label
        {
            Text = "›",
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleCenter,
            ForeColor = Accent,
            BackColor = WindowBackground,
            Font = new Font("Consolas", 17F, FontStyle.Bold),
            Margin = Padding.Empty
        }, 0, 0);

        _commandInput.Dock = DockStyle.Fill;
        _commandInput.BackColor = WindowBackground;
        _commandInput.ForeColor = DefaultText;
        _commandInput.BorderStyle = BorderStyle.FixedSingle;
        _commandInput.Font = new Font("Consolas", 11F);
        _commandInput.PlaceholderText = "Например: /spawn или /home";
        _commandInput.MaxLength = 256;
        _commandInput.Margin = new Padding(0, 0, 8, 0);
        _commandInput.KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Enter)
            {
                e.SuppressKeyPress = true;
                SendCommand();
            }
        };
        inputRow.Controls.Add(_commandInput, 1, 0);

        _sendCommandButton.Dock = DockStyle.Fill;
        _sendCommandButton.FlatStyle = FlatStyle.Flat;
        _sendCommandButton.FlatAppearance.BorderSize = 0;
        _sendCommandButton.BackColor = Color.FromArgb(91, 111, 207);
        _sendCommandButton.ForeColor = Color.White;
        _sendCommandButton.Font = new Font("Segoe UI Semibold", 9F);
        _sendCommandButton.Margin = Padding.Empty;
        _sendCommandButton.Click += (_, _) => SendCommand();
        inputRow.Controls.Add(_sendCommandButton, 2, 0);
        commandPanel.Controls.Add(inputRow, 0, 1);

        var commandFooter = new TableLayoutPanel
        {
            Dock = DockStyle.Top,
            AutoSize = true,
            ColumnCount = 2,
            RowCount = 1,
            Margin = Padding.Empty
        };
        commandFooter.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        commandFooter.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        commandFooter.Controls.Add(new Label
        {
            Text = "Enter — отправить  •  Команды выполняются с правами вашего аккаунта на сервере",
            ForeColor = MutedText,
            AutoSize = true,
            Font = new Font("Segoe UI", 8F),
            Margin = Padding.Empty
        }, 0, 0);
        _commandStatusLabel.Text = "Подключение к Minecraft…";
        _commandStatusLabel.ForeColor = MutedText;
        _commandStatusLabel.AutoSize = true;
        _commandStatusLabel.Font = new Font("Segoe UI", 8F);
        _commandStatusLabel.Margin = new Padding(10, 0, 0, 0);
        commandFooter.Controls.Add(_commandStatusLabel, 1, 0);
        commandPanel.Controls.Add(commandFooter, 0, 2);
        root.Controls.Add(commandPanel, 0, 3);

        _statusLabel.Dock = DockStyle.Fill;
        _statusLabel.AutoSize = true;
        _statusLabel.ForeColor = MutedText;
        _statusLabel.Padding = new Padding(0, 2, 0, 0);
        _statusLabel.Font = new Font("Segoe UI", 8F);
        root.Controls.Add(_statusLabel, 0, 4);
        UpdateStatus();
    }

    private async Task ConnectCommandBridgeAsync()
    {
        if (_commandPort <= 0 || string.IsNullOrWhiteSpace(_commandToken))
        {
            SetCommandStatus("Окно запущено отдельно от Minecraft.", false);
            return;
        }

        var client = new TcpClient();
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await client.ConnectAsync(IPAddress.Loopback, _commandPort, timeout.Token);
            var stream = client.GetStream();
            var reader = new StreamReader(stream, Encoding.UTF8, false, 1024, true);
            var writer = new StreamWriter(stream, new UTF8Encoding(false), 1024, true) { AutoFlush = true };

            await writer.WriteLineAsync($"HELLO {_commandToken}");
            string? handshake = await reader.ReadLineAsync().WaitAsync(timeout.Token);
            if (!string.Equals(handshake, "READY", StringComparison.Ordinal))
                throw new IOException(handshake ?? "Minecraft закрыл соединение.");

            _commandClient = client;
            _commandWriter = writer;
            _sendCommandButton.Enabled = true;
            _connectionIndicator.Text = "●  ИГРА ПОДКЛЮЧЕНА";
            _connectionIndicator.ForeColor = Color.FromArgb(92, 222, 160);
            SetCommandStatus("Подключено к активному клиенту Minecraft.", true);
            _ = Task.Run(() => ReadCommandResponses(reader, client));
        }
        catch (Exception error)
        {
            client.Dispose();
            _sendCommandButton.Enabled = false;
            _connectionIndicator.Text = "●  НЕТ СВЯЗИ С ИГРОЙ";
            _connectionIndicator.ForeColor = Color.FromArgb(255, 145, 145);
            SetCommandStatus($"Не удалось подключиться: {error.Message}", false);
        }
    }

    private void ReadCommandResponses(StreamReader reader, TcpClient client)
    {
        try
        {
            string? line;
            while ((line = reader.ReadLine()) != null)
            {
                string response = line;
                RunOnUiThread(() =>
                {
                    bool success = response.StartsWith("OK\t", StringComparison.Ordinal);
                    string message = response.Length > 3 ? response[3..] : response;
                    SetCommandStatus(message, success);
                });
            }
        }
        catch (IOException)
        {
            // The game or companion window was closed.
        }
        finally
        {
            client.Dispose();
            RunOnUiThread(() =>
            {
                _commandWriter = null;
                _sendCommandButton.Enabled = false;
                _connectionIndicator.Text = "●  НЕТ СВЯЗИ С ИГРОЙ";
                _connectionIndicator.ForeColor = Color.FromArgb(255, 145, 145);
                SetCommandStatus("Соединение с Minecraft закрыто. Откройте окно снова клавишей F8.", false);
            });
        }
    }

    private void RunOnUiThread(Action action)
    {
        if (IsDisposed || !IsHandleCreated) return;
        try { BeginInvoke(action); }
        catch (InvalidOperationException) { }
    }

    private void SetCommandStatus(string message, bool success)
    {
        _commandStatusLabel.Text = message;
        _commandStatusLabel.ForeColor = success
            ? Color.FromArgb(92, 222, 160)
            : Color.FromArgb(255, 145, 145);
    }

    private void SendCommand()
    {
        string command = _commandInput.Text.Trim();
        if (command.Length == 0)
        {
            SetCommandStatus("Введите команду, например /spawn.", false);
            _commandInput.Focus();
            return;
        }

        if (command.Length > 256)
        {
            SetCommandStatus("Команда слишком длинная (максимум 256 символов).", false);
            return;
        }

        lock (_commandWriteLock)
        {
            if (_commandWriter == null)
            {
                SetCommandStatus("Нет подключения к игре. Закройте и снова откройте окно через F8.", false);
                return;
            }

            try
            {
                _commandWriter.WriteLine(command);
                _commandInput.Clear();
                SetCommandStatus($"Отправлено: {command}", true);
            }
            catch (IOException error)
            {
                SetCommandStatus($"Ошибка отправки: {error.Message}", false);
            }
        }
    }

    private static Button MakeButton(string text)
    {
        var button = new Button { Text = text, AutoSize = true };
        StyleButton(button);
        return button;
    }

    private static void StyleButton(Button button)
    {
        button.FlatStyle = FlatStyle.Flat;
        button.FlatAppearance.BorderColor = Color.FromArgb(55, 69, 94);
        button.BackColor = Color.FromArgb(31, 42, 61);
        button.ForeColor = DefaultText;
        button.Font = new Font("Segoe UI", 8.5F);
        button.Padding = new Padding(8, 3, 8, 3);
        button.Margin = new Padding(0, 0, 6, 0);
    }

    private void LoadInitialTail()
    {
        try
        {
            using var stream = new FileStream(_logPath, FileMode.OpenOrCreate, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            long length = stream.Length;
            long start = Math.Max(0, length - InitialTailBytes);
            stream.Position = start;
            if (start > 0)
            {
                int value;
                while ((value = stream.ReadByte()) >= 0 && value != '\n') { }
            }

            using var memory = new MemoryStream();
            stream.CopyTo(memory);
            byte[] initialBytes = memory.ToArray();
            ProcessBytes(initialBytes, initialBytes.Length);
            _position = stream.Length;
            RebuildLog();
        }
        catch (Exception error)
        {
            ShowReadError(error);
        }
    }

    private void PollLog()
    {
        try
        {
            if (!File.Exists(_logPath)) return;
            using var stream = new FileStream(_logPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            if (stream.Length < _position)
            {
                _position = 0;
                _pendingBytes.Clear();
            }

            if (stream.Length <= _position) return;
            stream.Position = _position;
            byte[] buffer = new byte[64 * 1024];
            int read;
            bool changed = false;
            while ((read = stream.Read(buffer, 0, buffer.Length)) > 0)
            {
                _position += read;
                ProcessBytes(buffer, read);
                changed = true;
            }
            if (changed) UpdateStatus();
        }
        catch (Exception error)
        {
            ShowReadError(error);
        }
    }

    private void ProcessBytes(byte[] bytes, int count)
    {
        for (int i = 0; i < count; i++)
        {
            byte value = bytes[i];
            if (value == (byte)'\n')
            {
                if (_pendingBytes.Count > 0 && _pendingBytes[^1] == (byte)'\r')
                    _pendingBytes.RemoveAt(_pendingBytes.Count - 1);
                string line = Encoding.UTF8.GetString(_pendingBytes.ToArray());
                _pendingBytes.Clear();
                AddLine(line);
            }
            else
            {
                _pendingBytes.Add(value);
                if (_pendingBytes.Count > MaxPendingLineBytes)
                {
                    _pendingBytes.Clear();
                    AddLine("[GreetConsole] Пропущена слишком длинная строка журнала (более 2 МБ).");
                }
            }
        }
    }

    private void AddLine(string line)
    {
        if (line.Length == 0) return;
        _history.Add(line);
        if (_history.Count > MaxHistory) _history.RemoveAt(0);
        if (!_paused && MatchesLevel(line))
        {
            AppendColoredLine(line);
            if (_autoScroll.Checked) ScrollToEnd();
        }
        UpdateStatus();
    }

    private void RebuildLog()
    {
        if (_paused) return;
        _logView.Clear();
        foreach (string line in _history)
        {
            if (MatchesLevel(line)) AppendColoredLine(line);
        }
        if (_autoScroll.Checked) ScrollToEnd();
        HighlightSearch();
        UpdateStatus();
    }

    private bool MatchesLevel(string line)
    {
        if (_levelFilter.SelectedIndex <= 0) return true;
        string target = _levelFilter.SelectedItem?.ToString() ?? "Все уровни";
        string actual = Regex.Match(line, @"\[(?:[^\]]*/)?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\]", RegexOptions.IgnoreCase).Groups[1].Value.ToUpperInvariant();
        if (actual.Length == 0) actual = "OTHER";
        return string.Equals(actual, target, StringComparison.OrdinalIgnoreCase);
    }

    private void AppendColoredLine(string line)
    {
        Color color = DefaultText;
        bool bold = false, italic = false, underline = false, strike = false;
        var segment = new StringBuilder();

        void Flush()
        {
            if (segment.Length == 0) return;
            _logView.SelectionStart = _logView.TextLength;
            _logView.SelectionLength = 0;
            _logView.SelectionColor = color;
            FontStyle style = FontStyle.Regular;
            if (bold) style |= FontStyle.Bold;
            if (italic) style |= FontStyle.Italic;
            if (underline) style |= FontStyle.Underline;
            if (strike) style |= FontStyle.Strikeout;
            _logView.SelectionFont = new Font(_logView.Font, style);
            _logView.AppendText(segment.ToString());
            segment.Clear();
        }

        for (int i = 0; i < line.Length;)
        {
            if (TryParseMinecraftCode(line, i, out int consumed, out string code))
            {
                Flush();
                ApplyMinecraftCode(code, ref color, ref bold, ref italic, ref underline, ref strike);
                i += consumed;
                continue;
            }

            if (line[i] == '\u001b' && TryParseAnsi(line, i, out int ansiConsumed, out int[] ansiCodes))
            {
                Flush();
                ApplyAnsiCodes(ansiCodes, ref color, ref bold, ref italic, ref underline, ref strike);
                i += ansiConsumed;
                continue;
            }

            segment.Append(line[i]);
            i++;
        }

        Flush();
        _logView.SelectionStart = _logView.TextLength;
        _logView.SelectionLength = 0;
        _logView.SelectionColor = DefaultText;
        _logView.SelectionFont = new Font(_logView.Font, FontStyle.Regular);
        _logView.AppendText(Environment.NewLine);
    }

    private static bool TryParseMinecraftCode(string text, int index, out int consumed, out string code)
    {
        consumed = 0;
        code = "";
        if (index + 1 >= text.Length || (text[index] != '&' && text[index] != '§')) return false;
        char next = char.ToLowerInvariant(text[index + 1]);

        if (next == '#' && index + 8 <= text.Length && IsHex(text.AsSpan(index + 2, 6)))
        {
            code = "#" + text.Substring(index + 2, 6);
            consumed = 8;
            return true;
        }

        if (next == 'x')
        {
            int cursor = index + 2;
            var hex = new StringBuilder(6);
            for (int n = 0; n < 6; n++)
            {
                if (cursor + 1 >= text.Length || (text[cursor] != '&' && text[cursor] != '§') || !Uri.IsHexDigit(text[cursor + 1]))
                    return false;
                hex.Append(text[cursor + 1]);
                cursor += 2;
            }
            code = "#" + hex;
            consumed = cursor - index;
            return true;
        }

        if ("0123456789abcdefklmnor".Contains(next))
        {
            code = next.ToString();
            consumed = 2;
            return true;
        }

        return false;
    }

    private static bool IsHex(ReadOnlySpan<char> value)
    {
        foreach (char character in value)
            if (!Uri.IsHexDigit(character)) return false;
        return true;
    }

    private static void ApplyMinecraftCode(string code, ref Color color, ref bool bold, ref bool italic, ref bool underline, ref bool strike)
    {
        if (code.StartsWith('#'))
        {
            color = ColorTranslator.FromHtml(code);
            return;
        }

        char value = code[0];
        int colorIndex = "0123456789abcdef".IndexOf(value);
        if (colorIndex >= 0)
        {
            color = MinecraftColors[colorIndex];
            bold = italic = underline = strike = false;
            return;
        }

        switch (value)
        {
            case 'l': bold = true; break;
            case 'o': italic = true; break;
            case 'n': underline = true; break;
            case 'm': strike = true; break;
            case 'r':
                color = DefaultText;
                bold = italic = underline = strike = false;
                break;
            // &k / §k is intentionally stripped; obfuscated log output is not useful.
        }
    }

    private static bool TryParseAnsi(string text, int index, out int consumed, out int[] codes)
    {
        consumed = 0;
        codes = [];
        if (index + 2 >= text.Length || text[index] != '\u001b' || text[index + 1] != '[') return false;
        int end = text.IndexOf('m', index + 2);
        if (end < 0) return false;
        string raw = text.Substring(index + 2, end - index - 2);
        codes = raw.Split(';', StringSplitOptions.RemoveEmptyEntries).Select(s => int.TryParse(s, out int number) ? number : 0).ToArray();
        if (codes.Length == 0) codes = [0];
        consumed = end - index + 1;
        return true;
    }

    private static void ApplyAnsiCodes(int[] codes, ref Color color, ref bool bold, ref bool italic, ref bool underline, ref bool strike)
    {
        for (int i = 0; i < codes.Length; i++)
        {
            int code = codes[i];
            if (code == 0) { color = DefaultText; bold = italic = underline = strike = false; }
            else if (code == 1) bold = true;
            else if (code == 3) italic = true;
            else if (code == 4) underline = true;
            else if (code == 9) strike = true;
            else if (code == 22) bold = false;
            else if (code == 23) italic = false;
            else if (code == 24) underline = false;
            else if (code == 29) strike = false;
            else if (code is >= 30 and <= 37) color = MinecraftColors[code - 30];
            else if (code is >= 90 and <= 97) color = MinecraftColors[code - 90 + 8];
            else if (code == 39) color = DefaultText;
            else if (code == 38 && i + 2 < codes.Length && codes[i + 1] == 5)
            {
                color = Ansi256(codes[i + 2]);
                i += 2;
            }
            else if (code == 38 && i + 4 < codes.Length && codes[i + 1] == 2)
            {
                color = Color.FromArgb(ClampByte(codes[i + 2]), ClampByte(codes[i + 3]), ClampByte(codes[i + 4]));
                i += 4;
            }
        }
    }

    private static int ClampByte(int value) => Math.Clamp(value, 0, 255);

    private static Color Ansi256(int index)
    {
        index = Math.Clamp(index, 0, 255);
        if (index < 16) return MinecraftColors[index];
        if (index >= 232)
        {
            int gray = 8 + (index - 232) * 10;
            return Color.FromArgb(gray, gray, gray);
        }
        int value = index - 16;
        int[] levels = [0, 95, 135, 175, 215, 255];
        return Color.FromArgb(levels[value / 36], levels[(value / 6) % 6], levels[value % 6]);
    }

    private void HighlightSearch()
    {
        string query = _searchBox.Text;
        if (_logView.TextLength == 0) return;
        int selectionStart = _logView.SelectionStart;
        _logView.SelectAll();
        _logView.SelectionBackColor = WindowBackground;
        if (query.Length > 0)
        {
            string text = _logView.Text;
            int index = 0;
            while ((index = text.IndexOf(query, index, StringComparison.OrdinalIgnoreCase)) >= 0)
            {
                _logView.Select(index, query.Length);
                _logView.SelectionBackColor = Color.FromArgb(86, 72, 34);
                index += query.Length;
            }
        }
        _logView.SelectionStart = Math.Min(selectionStart, _logView.TextLength);
        _logView.SelectionLength = 0;
    }

    private void ScrollToEnd()
    {
        _logView.SelectionStart = _logView.TextLength;
        _logView.SelectionLength = 0;
        _logView.ScrollToCaret();
    }

    private void ShowReadError(Exception error)
    {
        _statusLabel.Text = $"Ошибка чтения журнала: {error.Message}";
        _statusLabel.ForeColor = Color.FromArgb(255, 120, 120);
    }

    private void UpdateStatus()
    {
        _statusLabel.Text = $"{(_paused ? "ПАУЗА" : "LIVE")}  •  Строк в памяти: {_history.Count:N0}  •  {_logPath}";
        _statusLabel.ForeColor = _paused ? Color.FromArgb(255, 190, 95) : Color.FromArgb(92, 222, 160);
    }
}
