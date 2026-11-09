namespace GreetConsole;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        string logPath = ReadLogPath(args);
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(logPath));
    }

    private static string ReadLogPath(string[] args)
    {
        for (int i = 0; i + 1 < args.Length; i++)
        {
            if (string.Equals(args[i], "--log", StringComparison.OrdinalIgnoreCase))
                return Path.GetFullPath(args[i + 1]);
        }

        return Path.Combine(AppContext.BaseDirectory, "latest.log");
    }
}
