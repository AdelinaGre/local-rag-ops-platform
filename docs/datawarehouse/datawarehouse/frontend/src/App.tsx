import { BrowserRouter, Route, Routes } from "react-router-dom";
import AppLayout from "./components/AppLayout";
import Dashboard from "./pages/Dashboard";
import Instruments from "./pages/Instruments";
import TimeSeries from "./pages/TimeSeries";
import Ingestion from "./pages/Ingestion";
import Analytics from "./pages/Analytics";
import Assistant from "./pages/Assistant";
import NotFound from "./pages/NotFound";

const App = () => (
  <BrowserRouter>
    <Routes>
      <Route element={<AppLayout />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/instruments" element={<Instruments />} />
        <Route path="/timeseries" element={<TimeSeries />} />
        <Route path="/ingestion" element={<Ingestion />} />
        <Route path="/analytics" element={<Analytics />} />
        <Route path="/assistant" element={<Assistant />} />
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  </BrowserRouter>
);

export default App;
