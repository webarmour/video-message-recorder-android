package io.webarmour.camerapipelinelab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun ChatDemoScreen(
    messages: List<String>,
    messageText: String,
    recordingStatus: String?,
    onMessageTextChange: (String) -> Unit,
    onSendText: () -> Unit,
    onVideoPressStart: () -> Unit,
    onVideoPressRelease: () -> Unit,
    onVideoPressCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.background
            ),
    ) {
        Text(
            text = "Тестовый чат",
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(
                8.dp
            ),
        ) {
            items(
                items = messages,
            ) { message ->
                Surface(
                    shape = RoundedCornerShape(
                        18.dp
                    ),
                    color =
                        MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(
                            horizontal = 14.dp,
                            vertical = 10.dp,
                        ),
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            recordingStatus?.let { status ->
                item {
                    Text(
                        text = status,
                        modifier = Modifier.padding(
                            vertical = 8.dp
                        ),
                        color =
                            MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }

        ChatDemoInput(
            text = messageText,
            onTextChange = onMessageTextChange,
            onSendText = onSendText,
            onVideoPressStart = onVideoPressStart,
            onVideoPressRelease = onVideoPressRelease,
            onVideoPressCancel = onVideoPressCancel,
        )
    }
}

@Composable
private fun ChatDemoMessage(
    text: String,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(
                18.dp
            ),
            tonalElevation = 2.dp,
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = 10.dp,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ChatDemoInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSendText: () -> Unit,
    onVideoPressStart: () -> Unit,
    onVideoPressRelease: () -> Unit,
    onVideoPressCancel: () -> Unit,
) {
    val currentOnVideoPressStart by rememberUpdatedState(
        onVideoPressStart
    )

    val currentOnVideoPressRelease by rememberUpdatedState(
        onVideoPressRelease
    )

    val currentOnVideoPressCancel by rememberUpdatedState(
        onVideoPressCancel
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                8.dp
            ),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        text = "Сообщение"
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        onSendText()
                    }
                ),
            )

            if (text.isBlank()) {
                Box(
                    modifier = Modifier
                        .size(
                            80.dp
                        )
                        .clip(
                            CircleShape
                        )
                        .background(
                            MaterialTheme.colorScheme.primaryContainer
                        )
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false
                                )

                                down.consume()

                                currentOnVideoPressStart()

                                val up =
                                    waitForUpOrCancellation()

                                if (up != null) {
                                    up.consume()

                                    currentOnVideoPressRelease()
                                } else {
                                    currentOnVideoPressCancel()
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "●",
                        style =
                            MaterialTheme.typography.headlineMedium,
                        color =
                            MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                IconButton(
                    onClick = onSendText,
                ) {
                    Text(
                        text = "➤",
                        style =
                            MaterialTheme.typography.headlineMedium,
                        color =
                            MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}