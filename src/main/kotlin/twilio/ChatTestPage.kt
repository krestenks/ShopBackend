package twilio

import DataBase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.html.*
import kotlinx.html.*

/**
 * Chat test page for testing the chatbot without Twilio
 */
fun Route.chatTestRoutes(db: DataBase, chatbotService: TwilioChatbotService) {

    get("/chat-test") {
        val shops = db.getAllShops()
        call.respondHtml {
            head {
                title("Chatbot Test")
                style {
                    unsafe {
                        +"""
                            body { font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; padding: 20px; }
                            #chat-container { border: 1px solid #ccc; height: 400px; overflow-y: auto; padding: 10px; margin-bottom: 10px; }
                            .message { padding: 8px 12px; margin: 5px 0; border-radius: 8px; max-width: 80%; }
                            .user { background: #007bff; color: white; margin-left: auto; }
                            .bot { background: #e9ecef; color: #333; }
                            #phone { width: 200px; padding: 8px; margin-bottom: 10px; }
                            #shop { width: 200px; padding: 8px; margin-bottom: 10px; }
                            #message { width: 70%; padding: 8px; }
                            #send { padding: 8px 20px; background: #28a745; color: white; border: none; cursor: pointer; }
                            .clear { padding: 8px 20px; background: #dc3545; color: white; border: none; cursor: pointer; margin-left: 10px; }
                            .controls { display: flex; gap: 10px; margin-bottom: 10px; }
                            .controls div { flex: 1; }
                        """.trimIndent()
                    }
                }
            }
            body {
                h1 { +"Chatbot Test Page" }
                div {
                    style = "margin-bottom: 10px;"
                    label { +"Shop: " }
                    select {
                        id = "shop"
                        for (shop in shops) {
                            option {
                                value = shop.id.toString()
                                +shop.name
                            }
                        }
                    }
                }
                div {
                    style = "margin-bottom: 10px;"
                    input(type = InputType.text) { id = "phone"; placeholder = "Your phone (e.g., +4512345678)"; style = "width: 200px; padding: 8px;" }
                }
                div {
                    id = "chat-container"
                }
                div {
                    input(type = InputType.text) { id = "message"; placeholder = "Type a message..."; style = "width: 70%; padding: 8px;" }
                    button(type = ButtonType.button) { id = "send"; +"Send" }
                    button(type = ButtonType.button) { id = "clear"; +"Clear" }
                }
                script {
                    unsafe {
                        +"""
                            const chatContainer = document.getElementById('chat-container');
                            const messageInput = document.getElementById('message');
                            const phoneInput = document.getElementById('phone');
                            const shopInput = document.getElementById('shop');
                            const sendBtn = document.getElementById('send');
                            const clearBtn = document.getElementById('clear');

                            function addMessage(text, isUser) {
                                const div = document.createElement('div');
                                div.className = 'message ' + (isUser ? 'user' : 'bot');
                                div.textContent = text;
                                chatContainer.appendChild(div);
                                chatContainer.scrollTop = chatContainer.scrollHeight;
                            }

                            function getCookie(name) {
                                const value = '; ' + document.cookie;
                                const parts = value.split('; ' + name + '=');
                                if (parts.length === 2) return parts.pop().split(';').shift();
                            }

                            async function sendMessage() {
                                const phone = phoneInput.value.trim();
                                const message = messageInput.value.trim();
                                const shopId = shopInput.value;
                                if (!phone || !message) {
                                    alert('Please enter both phone and message');
                                    return;
                                }

                                addMessage(message, true);
                                messageInput.value = '';

                                try {
                                    const response = await fetch('/api/chat/send', {
                                        method: 'POST',
                                        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                                        body: 'phone=' + encodeURIComponent(phone) + 
                                              '&message=' + encodeURIComponent(message) + 
                                              '&shop_id=' + encodeURIComponent(shopId)
                                    });
                                    const data = await response.json();
                                    addMessage(data.response, false);
                                } catch (e) {
                                    addMessage('Error: ' + e.message, false);
                                }
                            }

                            sendBtn.addEventListener('click', sendMessage);
                            clearBtn.addEventListener('click', () => {
                                chatContainer.innerHTML = '';
                            });

                            messageInput.addEventListener('keypress', (e) => {
                                if (e.key === 'Enter') sendMessage();
                            });
                        """.trimIndent()
                    }
                }
            }
        }
    }
}
