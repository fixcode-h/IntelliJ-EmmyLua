#pragma once

#include "command_line.h"

class EmmyTool {
public:
	explicit EmmyTool(CommandLine &cmd);

	int Attach();
	int AttachV3();

	int ListProcesses();

	int ArchFile();

	int ArchPid();

	int Capabilities();

private:

	CommandLine _cmd;
};

