'use client';

import React, { useEffect, useRef, useState } from 'react';
import { Bot, Loader2, Mic, MicOff, Send, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { cn } from '@/lib/utils';
import { type ChatMessage, type ChatTurn, type ExtractedIncidentFields, incidentService } from '../services/incident-service';
import { IncidentError } from '../model/types';

interface Props {
  onReady: (fields: ExtractedIncidentFields) => void;
}

export function ChatIntake({ onReady }: Props) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [extracted, setExtracted] = useState<ExtractedIncidentFields | null>(null);
  const [isListening, setIsListening] = useState(false);
  const [voiceSupported, setVoiceSupported] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const recognitionRef = useRef<any>(null);

  useEffect(() => {
    // SpeechRecognition is browser-only; check after mount to avoid SSR issues.
    setVoiceSupported(
      typeof window !== 'undefined' &&
      !!((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition)
    );
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const send = async () => {
    const text = input.trim();
    if (!text || sending) return;
    const userMsg: ChatMessage = { role: 'user', content: text };
    const next = [...messages, userMsg];
    setMessages(next);
    setInput('');
    setSending(true);
    try {
      // Send only the last 20 messages to keep the request size bounded
      const turn: ChatTurn = await incidentService.chat(next.slice(-20));
      setMessages([...next, { role: 'assistant', content: turn.reply }]);
      if (turn.extracted) setExtracted(turn.extracted);
    } catch (err) {
      const isConfigError = err instanceof IncidentError && err.message.toLowerCase().includes('not configured');
      const errorMsg = isConfigError
        ? 'AI chat is not configured on this server. Please use the form to report your incident.'
        : 'Sorry, I hit an error. Please try again or use the form instead.';
      setMessages([...next, { role: 'assistant', content: errorMsg }]);
    } finally {
      setSending(false);
    }
  };

  const handleKey = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
  };

  const startVoice = () => {
    const SpeechRecognitionAPI = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    const recognition = new SpeechRecognitionAPI();
    recognition.continuous = false;
    recognition.interimResults = false;
    recognition.lang = 'en-US';
    recognition.onstart = () => setIsListening(true);
    recognition.onend = () => setIsListening(false);
    recognition.onerror = () => setIsListening(false);
    recognition.onresult = (e: any) => {
      const transcript: string = e.results[0][0].transcript;
      setInput(prev => (prev.trim() ? `${prev.trim()} ${transcript}` : transcript));
    };
    recognitionRef.current = recognition;
    recognition.start();
  };

  const stopVoice = () => {
    recognitionRef.current?.stop();
    setIsListening(false);
  };

  return (
    <div className="flex flex-col gap-3">
      {/* Message thread */}
      <div className="flex flex-col gap-3 max-h-96 overflow-y-auto pr-1">
        {messages.map((m, i) => (
          <div key={i} className={cn('flex gap-2.5', m.role === 'user' ? 'justify-end' : 'justify-start')}>
            {m.role === 'assistant' && (
              <span className="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/40 dark:text-blue-300">
                <Bot className="h-4 w-4" aria-hidden="true" />
              </span>
            )}
            <div className={cn(
              'max-w-[80%] rounded-2xl px-3.5 py-2.5 text-sm leading-relaxed',
              m.role === 'user'
                ? 'bg-primary text-primary-foreground rounded-br-sm'
                : 'bg-muted text-foreground rounded-bl-sm',
            )}>
              {m.content}
            </div>
          </div>
        ))}
        {sending && (
          <div className="flex gap-2.5 justify-start">
            <span className="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-blue-100 text-blue-600 dark:bg-blue-900/40 dark:text-blue-300">
              <Bot className="h-4 w-4" aria-hidden="true" />
            </span>
            <div className="rounded-2xl rounded-bl-sm bg-muted px-3.5 py-2.5">
              <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" aria-label="Thinking…" />
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      {/* Pre-fill banner */}
      {extracted?.ready && (
        <div className="flex items-center justify-between gap-3 rounded-lg border border-green-200 bg-green-50 px-3.5 py-2.5 text-sm dark:border-green-800 dark:bg-green-950/30">
          <span className="flex items-center gap-1.5 font-medium text-green-800 dark:text-green-200">
            <Sparkles className="h-4 w-4 shrink-0" aria-hidden="true" />
            Form is ready to fill in
          </span>
          <Button size="sm" onClick={() => onReady(extracted)}>
            Pre-fill form
          </Button>
        </div>
      )}

      {/* Input row */}
      <div className="flex gap-2">
        <Textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKey}
          placeholder={isListening ? 'Listening…' : 'Describe your problem… (Enter to send)'}
          rows={2}
          className={cn('resize-none', isListening && 'ring-2 ring-red-400')}
          disabled={sending}
          aria-label="Chat message"
        />
        {voiceSupported && (
          <Button
            type="button"
            size="icon"
            variant={isListening ? 'destructive' : 'outline'}
            onClick={isListening ? stopVoice : startVoice}
            disabled={sending}
            aria-label={isListening ? 'Stop recording' : 'Voice input'}
            className="self-end"
          >
            {isListening ? <MicOff className="h-4 w-4" /> : <Mic className="h-4 w-4" />}
          </Button>
        )}
        <Button
          type="button"
          size="icon"
          onClick={send}
          disabled={!input.trim() || sending}
          aria-label="Send"
          className="self-end"
        >
          {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
        </Button>
      </div>
    </div>
  );
}
