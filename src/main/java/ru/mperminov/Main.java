package ru.mperminov;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.ServiceAccountCredentials;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream("src/app.env")) {
            properties.load(input);
            logger.info("Application properties loaded from src/app.env");
        } catch (IOException ex) {
            logger.error("Failed to load app.env properties.", ex);
            return null;
        }
        return properties;
    }

    public static void main(String[] args) {
        logger.info("Application starting...");
        Properties properties = loadProperties();
        if (properties == null) {
            logger.error("Application failed to start due to missing properties.");
            return;
        }
        try {
            String botToken = properties.getProperty("tg.bot.token");
            TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
            botsApplication.registerBot(botToken, new HomelessGoogleSheetsBot(properties));
            logger.info("Telegram bot registered successfully.");
        } catch (TelegramApiException e) {
            logger.error("Failed to register Telegram bot.", e);
        }
        logger.info("Application started successfully.");
    }
}

class HomelessGoogleSheetsBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger logger = LoggerFactory.getLogger(HomelessGoogleSheetsBot.class);
    private static final int DEFAULT_ROW_COUNT = 5; // Number of last rows to fetch
    private static final String APPLICATION_NAME = "Telegram Bot Sheets";
    private static final String CREDENTIALS_FILE_PATH = "src/main/resources/credentials.json";

    public static Sheets getSheetsService() throws IOException, GeneralSecurityException {
        ServiceAccountCredentials credentials = ServiceAccountCredentials
                .fromStream(new FileInputStream(CREDENTIALS_FILE_PATH));
        logger.info("Google Sheets service credentials loaded from {}", CREDENTIALS_FILE_PATH);
        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    @NotNull
    private static SendMessage getSendMessage(final long chatId, final List<String> sheetNames) {
        SendMessage message = new SendMessage(String.valueOf(chatId), "Пожалуйста, выберите лист:");

        List<KeyboardRow> keyboard = new ArrayList<>();
        for (String sheetName : sheetNames) {
            KeyboardRow row = new KeyboardRow();
            row.add(sheetName);
            keyboard.add(row);
        }
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup(keyboard);
        keyboardMarkup.setResizeKeyboard(true);
        keyboardMarkup.setOneTimeKeyboard(true);
        message.setReplyMarkup(keyboardMarkup);
        return message;
    }
    private final String botToken;
    private final String spreadsheetId;
    private final Map<Long, UserState> userStates = new HashMap<>();
    private TelegramClient telegramClient;
    private Sheets sheetsService;

    public HomelessGoogleSheetsBot(Properties properties) {
        this.botToken = properties.getProperty("tg.bot.token");
        this.spreadsheetId = properties.getProperty("spreadsheet.id");
        this.telegramClient = new OkHttpTelegramClient(this.botToken);
        try {
            sheetsService = getSheetsService();
            logger.info("HomelessGoogleSheetsBot initialized successfully. Spreadsheet ID: {}", spreadsheetId);
        } catch (Exception e) {
            logger.error("Error initializing HomelessGoogleSheetsBot or Google Sheets service.", e);
        }
    }

    private String quoteSheetName(String sheetName) {
        return URLEncoder.encode(sheetName, StandardCharsets.UTF_16);
    }

    @Override
    public void consume(final Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            int messageId = update.getMessage().getMessageId();
            logger.info("Received message from chatId {}: {}", chatId, messageText);
            try {
                if (messageText.equals("/start")) {
                    logger.debug("Processing /start command for chatId {}", chatId);
                    sendStartMessage(chatId);
                } else if (messageText.equals("/sheets")) {
                    logger.debug("Processing /sheets command for chatId {}", chatId);
                    listAvailableSheets(chatId);
                } else {
                    logger.debug("Processing user input for chatId {}: {}", chatId, messageText);
                    processUserInput(chatId, messageText, messageId);
                }
            } catch (Exception e) {
                logger.error("Error processing message from chatId {}: {}", chatId, messageText, e);
                sendErrorMessage(chatId, "Ошибка при обработке вашего запроса: " + e.getMessage());
            }
        } else if (update.hasCallbackQuery()) {
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            String callbackData = update.getCallbackQuery().getData();
            int messageId = update.getCallbackQuery().getMessage().getMessageId();
            logger.info("Received callback query from chatId {}: {}", chatId, callbackData);
            try {
                processCallbackQuery(chatId, callbackData, messageId);
            } catch (Exception e) {
                logger.error("Error processing callback query from chatId {}: {}", chatId, callbackData, e);
                sendErrorMessage(chatId, "Ошибка при обработке вашего выбора: " + e.getMessage());
            }
        }
    }

    private void sendStartMessage(long chatId) throws TelegramApiException {
        SendMessage message = new SendMessage(String.valueOf(chatId), "Добро пожаловать в Homeless Sheets Bot!\n\n" +
                "Используйте /sheets, чтобы увидеть доступные таблицы.");
        execute(message);
        logger.debug("Sent start message to chatId {}", chatId);
    }

    private void sendMessage(long chatId, String text) throws TelegramApiException {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("Markdown");
        execute(message);
        logger.debug("Sent message to chatId {}: {}", chatId, text.lines().findFirst().orElse(""));
    }

    private void sendErrorMessage(long chatId, String errorText) {
        try {
            SendMessage message = new SendMessage(String.valueOf(chatId), "❌ " + errorText);
            execute(message);
            logger.warn("Sent error message to chatId {}: {}", chatId, errorText);
        } catch (TelegramApiException e) {
            logger.error("Failed to send error message to chatId {}", chatId, e);
        }
    }

    private void execute(SendMessage message) throws TelegramApiException {
        try {
            telegramClient.execute(message);
            logger.trace("Executed SendMessage to chatId {}", message.getChatId());
        } catch (TelegramApiException e) {
            logger.error("Failed to execute SendMessage to chatId {}", message.getChatId(), e);
            throw new TelegramApiException("Не удалось отправить сообщение", e);
        }
    }

    private void execute(EditMessageText message) throws TelegramApiException {
        try {
            telegramClient.execute(message);
            logger.trace("Executed EditMessageText for chatId {} messageId {}", message.getChatId(), message.getMessageId());
        } catch (TelegramApiException e) {
            logger.error("Failed to execute EditMessageText for chatId {} messageId {}",
                    message.getChatId(),
                    message.getMessageId(),
                    e);
            throw new TelegramApiException("Не удалось изменить сообщение", e);
        }
    }

    private void listAvailableSheets(long chatId) throws IOException, TelegramApiException {
        logger.debug("Listing available sheets for chatId {}", chatId);
        Spreadsheet spreadsheet = sheetsService.spreadsheets().get(this.spreadsheetId).execute();
        List<Sheet> sheets = spreadsheet.getSheets();

        if (sheets.isEmpty()) {
            logger.warn("No sheets found in spreadsheet {} for chatId {}", this.spreadsheetId, chatId);
            sendMessage(chatId, "В этой электронной таблице не найдено листов.");
            return;
        }

        List<String> sheetNames = new ArrayList<>();
        for (Sheet sheet : sheets) {
            sheetNames.add(sheet.getProperties().getTitle());
        }
        logger.info("Found sheets for chatId {}: {}", chatId, sheetNames);

        userStates.put(chatId, new UserState(UserState.State.SELECTING_SHEET, sheetNames));
        logger.debug("Updated user state for chatId {} to SELECTING_SHEET", chatId);

        SendMessage message = getSendMessage(chatId, sheetNames);

        execute(message);
    }

    private void processUserInput(long chatId, String messageText, int messageId) throws IOException, TelegramApiException {
        UserState userState = userStates.getOrDefault(chatId, new UserState(UserState.State.IDLE, null));
        logger.debug("Processing user input for chatId {} with state {}. Message: {}", chatId, userState.getState(), messageText);

        if (userState.getState() == UserState.State.SELECTING_SHEET) {
            List<String> availableSheets = userState.getAvailableSheets();
            if (availableSheets != null && availableSheets.contains(messageText)) {
                logger.info("User {} selected sheet: {}", chatId, messageText);
                fetchSheetData(chatId, messageText, DEFAULT_ROW_COUNT);
            } else {
                logger.warn("User {} selected an invalid sheet: {}. Available: {}", chatId, messageText, availableSheets);
                sendMessage(chatId,
                        "Пожалуйста, выберите корректный лист из списка или используйте /sheets, чтобы увидеть доступные варианты.");
            }
        } else if (userState.getState() == UserState.State.AWAITING_NEW_VALUE_FOR_EDIT) {
            logger.info("User {} provided new value for edit: {}", chatId, messageText);
            processProvidedValueForEdit(chatId, messageText, userState, messageId);
        } else {
            logger.warn("User {} sent message in unexpected state {}: {}", chatId, userState.getState(), messageText);
            sendMessage(chatId,
                    "Используйте /sheets, чтобы выбрать Google Таблицу для просмотра, или следуйте подсказкам, если вы добавляете новую строку.");
        }
    }

    private void fetchSheetData(long chatId, String sheetName, int rowCount) throws IOException, TelegramApiException {
        logger.info("Fetching sheet data for chatId {}, sheet: {}, rowCount: {}", chatId, sheetName, rowCount);
        String range = quoteSheetName(sheetName);

        ValueRange response = sheetsService.spreadsheets().values().get(this.spreadsheetId, range).execute();
        List<List<Object>> values = response.getValues();

        if (values == null || values.isEmpty()) {
            logger.warn("No data found in sheet {} for chatId {}", sheetName, chatId);
            sendMessage(chatId, "Нет данных в листе: " + sheetName);
            return;
        }
        logger.debug("Fetched {} rows from sheet {} for chatId {}", values.size(), sheetName, chatId);

        // Determine which rows to display
        int totalRows = values.size();
        List<List<Object>> lastRows;
        List<Object> headerRow = null;

        if (totalRows > 0) {
            headerRow = values.get(0); // Always get the header row
        }

        if (totalRows <= 1) { // Only header or no data
            lastRows = new ArrayList<>(); // No data rows to show if only header or empty
        } else if (totalRows <= rowCount + 1) { // Not enough rows to skip, show all data rows
            lastRows = values.subList(1, totalRows);
        } else { // Enough rows to show only the last 'rowCount' data rows
            lastRows = values.subList(totalRows - rowCount, totalRows);
        }

        // Build a message from the rows
        StringBuilder sb = new StringBuilder();
        sb.append("Последние ").append(lastRows.size()).append(" строк из *").append(sheetName).append("*:\n\n");

        // Determine column widths for alignment
        List<Integer> colWidths = new ArrayList<>();
        if (headerRow != null) {
            for (Object cell : headerRow) {
                colWidths.add(String.valueOf(cell).length());
            }
        }
        for (List<Object> row : lastRows) {
            for (int i = 0; i < row.size(); i++) {
                int cellLength = String.valueOf(row.get(i)).length();
                if (i < colWidths.size()) {
                    if (cellLength > colWidths.get(i)) {
                        colWidths.set(i, cellLength);
                    }
                } else {
                    colWidths.add(cellLength);
                }
            }
        }

        // Optional: Show headers if available
        if (headerRow != null && !headerRow.isEmpty()) {
            for (int i = 0; i < headerRow.size(); i++) {
                String headerCell = String.valueOf(headerRow.get(i));
                sb.append(String.format("`%-" + (i < colWidths.size() ? colWidths.get(i) : headerCell.length()) + "s`",
                        headerCell)).append(" | ");
            }
            if (sb.length() > 3) {
                sb.setLength(sb.length() - 3); // Remove trailing " | "
            }
            sb.append("\n");

            for (int i = 0; i < headerRow.size(); i++) {
                sb.append(String.format("%-" + (i < colWidths.size() ? colWidths.get(i) + 2 : 2) + "s", "").replace(' ', '-'))
                        .append("|");
            }
            if (sb.length() > 1) {
                sb.setLength(sb.length() - 1); // Remove trailing "|"
            }
            sb.append("\n");
        }

        for (List<Object> row : lastRows) {
            for (int i = 0; i < row.size(); i++) {
                String cell = String.valueOf(row.get(i));
                sb.append(String.format("%-" + (i < colWidths.size() ? colWidths.get(i) : cell.length()) + "s", cell))
                        .append(" | ");
            }
            if (sb.length() > 3) {
                sb.setLength(sb.length() - 3); // Remove trailing " | "
            }
            sb.append("\n");
        }

        SendMessage message = new SendMessage(String.valueOf(chatId), sb.toString());
        // Optionally enable Markdown or HTML formatting
        message.setParseMode("Markdown");

        // Add "Add New Row" button
        List<InlineKeyboardRow> rowInline = new ArrayList<>();
        InlineKeyboardButton addNewButton = new InlineKeyboardButton("➕ Добавить новую строку");
        addNewButton.setCallbackData("add_new_row_" + sheetName);
        rowInline.add(new InlineKeyboardRow(addNewButton));

        InlineKeyboardButton backButton = new InlineKeyboardButton("Назад");
        backButton.setCallbackData("back_to_sheet_selection");
        rowInline.add(new InlineKeyboardRow(backButton)); // Add the back button to a new row

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup(rowInline);
        message.setReplyMarkup(inlineKeyboardMarkup);

        // Store header and last row for potential editing
        // Use computeIfAbsent to ensure we are working with the instance in the map
        UserState userState = userStates.computeIfAbsent(chatId, k -> new UserState(UserState.State.IDLE, null));

        userState.setSheetNameForEditing(sheetName); // Keep track of which sheet this data is for

        if (headerRow != null && !headerRow.isEmpty()) {
            userState.setHeaderRowForEditing(new ArrayList<>(headerRow)); // store a copy
        } else {
            userState.setHeaderRowForEditing(new ArrayList<>()); // Ensure it's an empty list, not null
        }

        if (!lastRows.isEmpty()) {
            userState.setTemplateRowForEditing(new ArrayList<>(lastRows.get(
                    lastRows.size() - 1))); // store a copy of the actual last data row
        } else if (headerRow != null && !headerRow.isEmpty()) { // Template based on header if no data rows but header exists
            List<Object> emptyTemplate = new ArrayList<>();
            for (int i = 0; i < headerRow.size(); i++) {
                emptyTemplate.add("");
            }
            userState.setTemplateRowForEditing(emptyTemplate);
        } else {
            userState.setTemplateRowForEditing(new ArrayList<>()); // Ensure it's an empty list if no other template
        }

        // The userState object is already in the map (or was just added by computeIfAbsent)
        // and has been modified directly. No explicit userStates.put() is needed here for these changes.

        //sendMessage(chatId, sb.toString()); //This is now part of the message object
        execute(message); // Send message with inline button
    }

    private void processCallbackQuery(long chatId, String callbackData, int messageId) throws IOException, TelegramApiException {
        UserState userState = userStates.get(chatId);
        if (userState == null) {
            sendErrorMessage(chatId, "Состояние сессии не найдено. Пожалуйста, начните сначала с команды /start или /sheets.");
            return;
        }

        if (callbackData.startsWith("add_new_row_")) {
            String sheetNameFromCallback = callbackData.substring("add_new_row_".length());
            // Ensure we have the correct sheetName and header in userState
            // getHeaderRowForEditing() should now never be null due to constructor initialization
            if (userState.getHeaderRowForEditing().isEmpty()) {
                sendErrorMessage(chatId,
                        "Не удалось начать добавление новой строки. Информация о заголовках отсутствует или пуста. Пожалуйста, попробуйте /sheets еще раз с листом, у которого есть заголовок.");
                userState.setState(UserState.State.IDLE);
                userStates.put(chatId, userState); // Save the reset state
                return;
            }
            userState.setSheetNameForEditing(sheetNameFromCallback); // Confirm sheet name from callback
            userState.clearPendingEdits(); // Clear any previous edits
            userState.setState(UserState.State.AWAITING_COLUMN_TO_EDIT);
            userStates.put(chatId, userState); // Save state changes before prompting
            logger.debug("User {} state set to AWAITING_COLUMN_TO_EDIT for sheet {}", chatId, sheetNameFromCallback);
            promptForColumnSelection(chatId, userState, messageId, true);
        } else if (callbackData.startsWith("edit_column_index_")) {
            int columnIndex = Integer.parseInt(callbackData.substring("edit_column_index_".length()));
            userState.setColumnIndexBeingEdited(columnIndex);
            userState.setState(UserState.State.AWAITING_NEW_VALUE_FOR_EDIT);
            userStates.put(chatId, userState);
            logger.debug("User {} callback: edit_column_index_{}. State set to AWAITING_NEW_VALUE_FOR_EDIT", chatId, columnIndex);
            promptForNewValue(chatId, userState, messageId);
        } else if (callbackData.equals("done_editing")) {
            logger.info("User {} callback: done_editing. Appending row to sheet {}", chatId, userState.getSheetNameForEditing());
            appendRowToSheet(chatId, userState, messageId);
            listAvailableSheets(chatId);
            logger.debug("User {} state set to SELECTING_SHEET after done_editing", chatId);
        } else if (callbackData.equals("cancel_editing")) {
            logger.info("User {} callback: cancel_editing. Cancelling row addition.", chatId);
            // Remove the current message with inline keyboard by editing its text and removing keyboard.
            EditMessageText editText = new EditMessageText("Добавление строки отменено. Выберите лист:");
            editText.setChatId(String.valueOf(chatId));
            editText.setMessageId(messageId);
            editText.setReplyMarkup(null); 
            execute(editText);

            // Clear edits and go back to sheet selection
            userState.clearPendingEdits();
            listAvailableSheets(chatId); // This will send a new message with sheet options
            userState.setState(UserState.State.SELECTING_SHEET); // listAvailableSheets already sets this, but being explicit.
            userStates.put(chatId, userState); // Ensure state is saved
            logger.debug("User {} state set to SELECTING_SHEET after cancel_editing and listing sheets", chatId);
        } else if (callbackData.equals("back_to_sheet_selection")) {
            logger.info("User {} callback: back_to_sheet_selection.", chatId);
            listAvailableSheets(chatId);
            userState.setState(UserState.State.SELECTING_SHEET);
            userStates.put(chatId, userState);
            logger.debug("User {} state set to SELECTING_SHEET", chatId);
        } else if (callbackData.equals("back_to_column_selection")) {
            logger.info("User {} callback: back_to_column_selection.", chatId);
            userState.setState(UserState.State.AWAITING_COLUMN_TO_EDIT);
            userStates.put(chatId, userState); // Save state
            logger.debug("User {} state set to AWAITING_COLUMN_TO_EDIT", chatId);
            promptForColumnSelection(chatId, userState, messageId, true); // true to edit the current message
        }
    }

    private void promptForColumnSelection(long chatId, UserState userState, int messageId, boolean isFirstPrompt)
            throws TelegramApiException {
        logger.debug("Prompting user {} for column selection. isFirstPrompt: {}. Current edits: {}",
                chatId,
                isFirstPrompt,
                userState.getPendingEdits());
        List<Object> headers = userState.getHeaderRowForEditing();
        if (headers == null || headers.isEmpty()) { // Should be caught by earlier checks, but defensive
            logger.error("Cannot prompt for column selection for user {}: Headers are null or empty.", chatId);
            sendErrorMessage(chatId, "Невозможно определить столбцы для редактирования. Строка заголовков не найдена.");
            userState.setState(UserState.State.IDLE);
            userStates.put(chatId, userState);
            return;
        }

        StringBuilder sb = new StringBuilder("Текущий черновик новой строки:\n");
        List<Object> currentRowData = new ArrayList<>(userState.getTemplateRowForEditing());
        userState.getPendingEdits().forEach((index, value) -> {
            if (index < currentRowData.size()) {
                currentRowData.set(index, value);
            }
        });

        for (int i = 0; i < headers.size(); i++) {
            Object header = headers.get(i);
            Object value = (i < currentRowData.size()) ? currentRowData.get(i) : "(пусто)";
            sb.append(String.format("`%s`: %s\n", header, value));
        }
        sb.append("\nКакой столбец вы хотите установить/изменить?");

        List<InlineKeyboardRow> keyboard = new ArrayList<>();
        InlineKeyboardRow keyboardRow = new InlineKeyboardRow();

        for (int i = 0; i < headers.size(); i++) {
            // Each button on its own row for clarity in this version
            InlineKeyboardRow buttonRow = new InlineKeyboardRow();
            InlineKeyboardButton button = new InlineKeyboardButton(String.valueOf(headers.get(i)));
            button.setCallbackData("edit_column_index_" + i);
            buttonRow.add(button);
            keyboard.add(buttonRow);
        }

        InlineKeyboardRow finalActionsRow = new InlineKeyboardRow();
        InlineKeyboardButton doneButton = new InlineKeyboardButton("✅ Готово");
        doneButton.setCallbackData("done_editing");
        finalActionsRow.add(doneButton);

        InlineKeyboardButton cancelButton = new InlineKeyboardButton("❌ Отмена");
        cancelButton.setCallbackData("cancel_editing");
        finalActionsRow.add(cancelButton);

        // keyboard.add(keyboardRow); // This would add an empty row if keyboardRow wasn't populated correctly
        keyboard.add(finalActionsRow);

        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup(keyboard);

        if (isFirstPrompt) {
            EditMessageText editText = new EditMessageText(sb.toString());
            editText.setChatId(String.valueOf(chatId));
            editText.setMessageId(messageId);
            editText.setReplyMarkup(inlineKeyboardMarkup);
            editText.setParseMode("Markdown");
            execute(editText);
        } else {
            SendMessage sm = new SendMessage(String.valueOf(chatId), sb.toString());
            sm.setReplyMarkup(inlineKeyboardMarkup);
            sm.setParseMode("Markdown");
            execute(sm);
        }
    }

    private void promptForNewValue(long chatId, UserState userState, int messageId) throws TelegramApiException {
        List<Object> headers = userState.getHeaderRowForEditing();
        int columnIndex = userState.getColumnIndexBeingEdited();
        String columnName = String.valueOf(headers.get(columnIndex));
        logger.debug("Prompting user {} for new value for column '{}' (index {}).", chatId, columnName, columnIndex);

        // Get current value for context
        Object currentValue = userState.getPendingEdits().getOrDefault(columnIndex,
                (columnIndex < userState.getTemplateRowForEditing().size()
                        ? userState.getTemplateRowForEditing().get(columnIndex)
                        : ""));

        String text = String.format(
                "Редактирование столбца: *%s*\nТекущее значение: `%s`\n\nПожалуйста, отправьте новое значение для этого столбца.",
                columnName,
                currentValue);

        EditMessageText editText = new EditMessageText(text);
        editText.setChatId(String.valueOf(chatId));
        editText.setMessageId(messageId);
        // editText.setReplyMarkup(null); // Remove keyboard, wait for text input

        InlineKeyboardButton backToColumnSelectionButton = new InlineKeyboardButton("Назад");
        backToColumnSelectionButton.setCallbackData("back_to_column_selection");
        InlineKeyboardRow keyboardRow = new InlineKeyboardRow(backToColumnSelectionButton);
        List<InlineKeyboardRow> keyboard = new ArrayList<>();
        keyboard.add(keyboardRow);
        InlineKeyboardMarkup inlineKeyboardMarkup = new InlineKeyboardMarkup(keyboard);
        editText.setReplyMarkup(inlineKeyboardMarkup);

        editText.setParseMode("Markdown");
        execute(editText);
    }

    private void processProvidedValueForEdit(long chatId, String newValue, UserState userState, int lastBotMessageId)
            throws TelegramApiException, IOException {
        int columnIndex = userState.getColumnIndexBeingEdited();
        String columnName = String.valueOf(userState.getHeaderRowForEditing().get(columnIndex));
        logger.info("User {} provided value '{}' for column '{}' (index {}).", chatId, newValue, columnName, columnIndex);
        userState.setPendingEdit(columnIndex, newValue);
        userStates.put(chatId, userState);

        // Delete the user's message containing the new value (optional, for cleaner interface)
        // try {
        //     DeleteMessage deleteUserMessage = new DeleteMessage(String.valueOf(chatId), userMessageId); // userMessageId would be needed
        //     telegramClient.execute(deleteUserMessage);
        // } catch (TelegramApiException e) { e.printStackTrace(); /* Failed to delete, not critical */ }

        // String columnName = String.valueOf(userState.getHeaderRowForEditing().get(columnIndex));
        // String diffText = String.format("Updated *%s* from `%s` to `%s`.", columnName, originalValue, newValue); // Diff text was removed as per flow
        // sendMessage(chatId, diffText); 

        promptForColumnSelection(chatId, userState, lastBotMessageId, false);
    }

    private void appendRowToSheet(long chatId, UserState userState, int messageId) throws IOException, TelegramApiException {
        String sheetName = userState.getSheetNameForEditing();
        List<Object> newRow = new ArrayList<>(userState.getTemplateRowForEditing());
        logger.info("Appending new row to sheet {} for user {}. Current pending edits: {}",
                sheetName,
                chatId,
                userState.getPendingEdits());

        // Apply pending edits to the template row
        userState.getPendingEdits().forEach((index, value) -> {
            if (index < newRow.size()) {
                newRow.set(index, value);
            } else {
                while (newRow.size() <= index) {
                    newRow.add(null);
                }
                newRow.set(index, value);
            }
        });

        if (userState.getHeaderRowForEditing() != null && newRow.size() < userState.getHeaderRowForEditing().size()) {
            for (int i = newRow.size(); i < userState.getHeaderRowForEditing().size(); i++) {
                newRow.add("");
            }
        }

        ValueRange body = new ValueRange().setValues(List.of(newRow));
        sheetsService.spreadsheets().values()
                .append(this.spreadsheetId, quoteSheetName(sheetName), body)
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
                .execute();
        logger.info("Successfully appended row to sheet {} for user {}", sheetName, chatId);

        EditMessageText editText = new EditMessageText("✅ Новая строка успешно добавлена в " + sheetName + "!");
        editText.setChatId(String.valueOf(chatId));
        editText.setMessageId(messageId);
        editText.setReplyMarkup(null); // Remove inline keyboard
        execute(editText);

        // Optionally, resend the sheet data to show the new row
        // fetchSheetData(chatId, sheetName, DEFAULT_ROW_COUNT);
    }
}

class UserState {

    private static final Logger logger = LoggerFactory.getLogger(UserState.class);

    enum State {
        IDLE,
        SELECTING_SHEET, // User has typed /sheets, bot is waiting for a sheet name selection
        AWAITING_COLUMN_TO_EDIT, // User wants to add/edit a row, bot is waiting for column selection
        AWAITING_NEW_VALUE_FOR_EDIT, // User has selected a column, bot is waiting for the new value
    }

    private State state;
    private List<String> availableSheets; // Used when state is SELECTING_SHEET

    // Fields for the editing flow
    private String sheetNameForEditing;
    private List<Object> headerRowForEditing;
    private List<Object> templateRowForEditing; // Based on the last row of the sheet
    private Map<Integer, Object> pendingEdits; // Column index to new value
    private int columnIndexBeingEdited; // Index of the column currently being edited

    public UserState(State state, List<String> availableSheets) {
        this.state = state;
        this.availableSheets = availableSheets != null ? new ArrayList<>(availableSheets) : new ArrayList<>();
        this.pendingEdits = new HashMap<>();
        this.headerRowForEditing = new ArrayList<>(); // Initialize to empty
        this.templateRowForEditing = new ArrayList<>(); // Initialize to empty
    }


    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public List<String> getAvailableSheets() {
        if (availableSheets == null) { // Defensive null check though constructor initializes
            logger.warn("getAvailableSheets called when availableSheets is null. Initializing to empty list.");
            availableSheets = new ArrayList<>();
        }
        return availableSheets;
    }

    public String getSheetNameForEditing() {
        return sheetNameForEditing;
    }

    public void setSheetNameForEditing(String sheetNameForEditing) {
        this.sheetNameForEditing = sheetNameForEditing;
    }

    public List<Object> getHeaderRowForEditing() {
        return headerRowForEditing;
    }

    public void setHeaderRowForEditing(List<Object> headerRowForEditing) {
        this.headerRowForEditing = headerRowForEditing;
    }

    public List<Object> getTemplateRowForEditing() {
        if (templateRowForEditing == null) { // Defensive null check
            logger.warn("getTemplateRowForEditing called when templateRowForEditing is null. Initializing to empty list.");
            templateRowForEditing = new ArrayList<>();
        }
        return templateRowForEditing;
    }

    public void setTemplateRowForEditing(List<Object> templateRowForEditing) {
        this.templateRowForEditing = new ArrayList<>(templateRowForEditing); // Ensure mutable copy
    }

    public Map<Integer, Object> getPendingEdits() {
        if (pendingEdits == null) { // Defensive null check
            logger.warn("getPendingEdits called when pendingEdits is null. Initializing to empty map.");
            pendingEdits = new HashMap<>();
        }
        return pendingEdits;
    }

    public int getColumnIndexBeingEdited() {
        return columnIndexBeingEdited;
    }

    public void setColumnIndexBeingEdited(int columnIndexBeingEdited) {
        this.columnIndexBeingEdited = columnIndexBeingEdited;
    }

    public void setPendingEdit(int columnIndex, Object value) {
        this.pendingEdits.put(columnIndex, value);
    }

    public void clearPendingEdits() {
        if (pendingEdits != null) {
            this.pendingEdits.clear();
            logger.debug("Pending edits cleared.");
        } else {
            logger.warn("clearPendingEdits called when pendingEdits is null.");
            pendingEdits = new HashMap<>(); // Initialize if it was null
        }
    }
}