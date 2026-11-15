import { createApp } from 'vue'
import App from './App.vue'
import './style.css'

// IIFE entry for JavaFX WebView, which does not load `<script type="module">`.
createApp(App).mount('#app')
