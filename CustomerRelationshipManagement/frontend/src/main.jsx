import React from 'react';
import { createRoot } from 'react-dom/client';
import App from './App.jsx';
import './styles.css';
import { initNativeApp } from './native.js';
import { watchTableLabels } from './tableLabels.js';

initNativeApp();
watchTableLabels();

createRoot(document.getElementById('root')).render(<App />);
