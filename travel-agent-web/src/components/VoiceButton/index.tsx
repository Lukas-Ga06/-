import { useState } from 'react';
import { Button, Tooltip } from 'antd';
import { AudioOutlined } from '@ant-design/icons';

interface Props {
  onText: (text: string) => void;
}

/** 语音输入（浏览器 Web Speech API），不支持的浏览器自动隐藏 */
export default function VoiceButton({ onText }: Props) {
  const [recording, setRecording] = useState(false);
  const supported =
    typeof window !== 'undefined' && !!(window.SpeechRecognition || window.webkitSpeechRecognition);

  if (!supported) {
    return null;
  }

  const start = () => {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;
    const rec = new SR();
    rec.lang = 'zh-CN';
    rec.interimResults = false;
    rec.maxAlternatives = 1;
    rec.onresult = (e: any) => {
      const text = e.results?.[0]?.[0]?.transcript || '';
      if (text) onText(text);
    };
    rec.onend = () => setRecording(false);
    rec.onerror = () => setRecording(false);
    rec.start();
    setRecording(true);
  };

  return (
    <Tooltip title={recording ? '正在聆听…' : '语音输入'}>
      <Button
        shape="circle"
        icon={<AudioOutlined />}
        onClick={start}
        type={recording ? 'primary' : 'default'}
        danger={recording}
      />
    </Tooltip>
  );
}
