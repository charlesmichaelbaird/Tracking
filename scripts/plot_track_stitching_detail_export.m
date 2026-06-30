% plot_track_stitching_detail_export.m
%
% Load and plot a Track Stitching "Export values" folder.
%
% Usage:
%   1. Run this script from MATLAB.
%   2. Pick the folder created by the app's "Export values" button.
%
% Optional inputs before running:
%   trackStitchingExportDir = "C:\path\to\export_folder";
%   selectedEventIndex = 0;
%   selectedPairLabel = "TRK-001 -> TRK-002";
%
% Outputs left in the workspace:
%   eventTimes, segments, trackBank, pairBank, config
%   selectedPairRows, selectedTrackRows, analysisData

if ~exist("trackStitchingExportDir", "var") ...
        || strlength(string(trackStitchingExportDir)) == 0
    chosenDir = uigetdir(pwd, "Select Track Stitching detailed values export");
    if isequal(chosenDir, 0)
        error("No export folder selected.");
    end
    trackStitchingExportDir = string(chosenDir);
else
    trackStitchingExportDir = string(trackStitchingExportDir);
end

eventTimes = readDetailTable(trackStitchingExportDir, "event_times.csv");
segments = readDetailTable(trackStitchingExportDir, "segments.csv");
trackBank = readDetailTable(trackStitchingExportDir, "track_bank_values.csv");
pairBank = readDetailTable(trackStitchingExportDir, "pair_bank_values.csv");
config = readDetailTable(trackStitchingExportDir, "configuration.csv");

analysisData = struct();
analysisData.exportDir = trackStitchingExportDir;
analysisData.eventTimes = eventTimes;
analysisData.segments = segments;
analysisData.trackBank = trackBank;
analysisData.pairBank = pairBank;
analysisData.config = config;

if isempty(pairBank)
    warning("pair_bank_values.csv is empty. No plots were created.");
    return;
end

availableEvents = unique(pairBank.event_index, "stable");
if ~exist("selectedEventIndex", "var") || ~ismember(selectedEventIndex, availableEvents)
    selectedEventIndex = availableEvents(1);
end

eventRows = pairBank(pairBank.event_index == selectedEventIndex, :);
availablePairs = unique(string(eventRows.pair_label), "stable");
if isempty(availablePairs)
    warning("No pair rows are available for event %d.", selectedEventIndex);
    return;
end

if ~exist("selectedPairLabel", "var") ...
        || ~ismember(string(selectedPairLabel), availablePairs)
    selectedPairLabel = availablePairs(1);
else
    selectedPairLabel = string(selectedPairLabel);
end

selectedPairRows = eventRows(string(eventRows.pair_label) == selectedPairLabel, :);
selectedTrackRows = trackBank(trackBank.event_index == selectedEventIndex ...
    & string(trackBank.pair_label) == selectedPairLabel, :);

analysisData.selectedEventIndex = selectedEventIndex;
analysisData.selectedPairLabel = selectedPairLabel;
analysisData.selectedPairRows = selectedPairRows;
analysisData.selectedTrackRows = selectedTrackRows;

plotSelectedPairCosts(selectedPairRows, selectedPairLabel, selectedEventIndex);
plotSelectedPairTrackStates(selectedTrackRows, selectedPairLabel, selectedEventIndex);
plotEventPairOverview(eventRows, selectedEventIndex);

disp("Loaded Track Stitching detailed values into:");
disp("  eventTimes, segments, trackBank, pairBank, config");
disp("  selectedPairRows, selectedTrackRows, analysisData");

function T = readDetailTable(rootDir, fileName)
    path = fullfile(rootDir, fileName);
    if ~isfile(path)
        error("Missing required export file: %s", path);
    end
    T = readtable(path, "TextType", "string", "VariableNamingRule", "preserve");
end

function plotSelectedPairCosts(pairRows, pairLabel, eventIndex)
    if isempty(pairRows)
        return;
    end
    bankTime = pairRows.bank_time_seconds;

    figure("Name", "Track Stitching selected pair cost");
    tiledlayout(3, 1, "TileSpacing", "compact");

    nexttile;
    plot(bankTime, pairRows.negative_log_likelihood, "-o", "LineWidth", 1.2);
    hold on;
    plot(bankTime, pairRows.physics_aware_negative_log_likelihood, "-s", "LineWidth", 1.2);
    grid on;
    xlabel("Bank time (s)");
    ylabel("NLL");
    legend("Base NLL", "Physics-aware NLL", "Location", "best");
    title(sprintf("Event %d, %s: Gaussian likelihood", eventIndex, pairLabel), ...
        "Interpreter", "none");

    nexttile;
    yyaxis left;
    plot(bankTime, pairRows.physics_aware_bridge_geometry_log_det, "-o", ...
        "LineWidth", 1.2);
    ylabel("log det(G)");
    yyaxis right;
    plot(bankTime, pairRows.physics_aware_opportunity_cost, "-s", ...
        "LineWidth", 1.2);
    ylabel("Opportunity cost");
    grid on;
    xlabel("Bank time (s)");
    title("Gramian geometry and alpha-scaled opportunity");

    nexttile;
    plot(bankTime, pairRows.physics_aware_negative_log_likelihood, "-o", ...
        "LineWidth", 1.2);
    hold on;
    plot(bankTime, pairRows.physics_aware_opportunity_cost, "-s", ...
        "LineWidth", 1.2);
    plot(bankTime, pairRows.physics_aware_cost, "-^", "LineWidth", 1.4);
    [bestCost, bestIndex] = min(pairRows.physics_aware_cost);
    plot(bankTime(bestIndex), bestCost, "kp", "MarkerSize", 12, ...
        "MarkerFaceColor", "y");
    grid on;
    xlabel("Bank time (s)");
    ylabel("Cost");
    legend("Physics-aware NLL", "Gramian term", "Total C_{ij\ell}", ...
        "Minimum", "Location", "best");
    title("Full pairwise bank cost");
end

function plotSelectedPairTrackStates(trackRows, pairLabel, eventIndex)
    if isempty(trackRows)
        return;
    end
    roles = unique(string(trackRows.role), "stable");

    figure("Name", "Track Stitching selected pair states");
    tiledlayout(3, 1, "TileSpacing", "compact");
    componentNames = ["x", "y", "z"];
    stateColumns = ["state_0", "state_1", "state_2"];

    for component = 1:3
        nexttile;
        hold on;
        for roleIndex = 1:numel(roles)
            roleRows = trackRows(string(trackRows.role) == roles(roleIndex), :);
            plot(roleRows.bank_time_seconds, roleRows.(stateColumns(component)), ...
                "-o", "LineWidth", 1.2, "DisplayName", roles(roleIndex));
        end
        grid on;
        xlabel("Bank time (s)");
        ylabel(componentNames(component) + " position");
        legend("Location", "best", "Interpreter", "none");
        title(sprintf("Event %d, %s: %s state at bank times", ...
            eventIndex, pairLabel, componentNames(component)), "Interpreter", "none");
    end
end

function plotEventPairOverview(eventRows, eventIndex)
    if isempty(eventRows)
        return;
    end
    pairLabels = unique(string(eventRows.pair_label), "stable");

    figure("Name", "Track Stitching event pair overview");
    tiledlayout(3, 1, "TileSpacing", "compact");

    nexttile;
    hold on;
    for index = 1:numel(pairLabels)
        rows = eventRows(string(eventRows.pair_label) == pairLabels(index), :);
        plot(rows.bank_time_seconds, rows.negative_log_likelihood, "-o", ...
            "LineWidth", 1.0, "DisplayName", pairLabels(index));
    end
    grid on;
    xlabel("Bank time (s)");
    ylabel("Base NLL");
    legend("Location", "best", "Interpreter", "none");
    title(sprintf("Event %d: base NLL by pair", eventIndex));

    nexttile;
    hold on;
    for index = 1:numel(pairLabels)
        rows = eventRows(string(eventRows.pair_label) == pairLabels(index), :);
        plot(rows.bank_time_seconds, rows.physics_aware_bridge_geometry_log_det, ...
            "-o", "LineWidth", 1.0, "DisplayName", pairLabels(index));
    end
    grid on;
    xlabel("Bank time (s)");
    ylabel("log det(G)");
    legend("Location", "best", "Interpreter", "none");
    title("Gramian bridge geometry by pair");

    nexttile;
    hold on;
    for index = 1:numel(pairLabels)
        rows = eventRows(string(eventRows.pair_label) == pairLabels(index), :);
        plot(rows.bank_time_seconds, rows.physics_aware_cost, "-o", ...
            "LineWidth", 1.0, "DisplayName", pairLabels(index));
    end
    grid on;
    xlabel("Bank time (s)");
    ylabel("Total C_{ij\ell}");
    legend("Location", "best", "Interpreter", "none");
    title("Full Physics-Aware pairwise cost by pair");
end
