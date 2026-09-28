import { router } from "./router/index.js";
import { createApp } from "vue";
import App from "./App.vue";
import "./styles/main.css";
createApp(App).use(router).mount("#app");
