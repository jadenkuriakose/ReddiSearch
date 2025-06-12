import React, { useState } from 'react';
import './App.css';

function App() {
  const [searchQuery, setSearchQuery] = useState('');
  const [subreddit, setSubreddit] = useState('');
  const [result, setResult] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);
  const [error, setError] = useState(null);

  const handleSearch = () => {
    if (!searchQuery.trim()) {
      setResult(null);
      setHasSearched(false);
      setError(null);
      return;
    }

    setIsLoading(true);
    setHasSearched(true);
    setError(null);

    const apiUrl = subreddit 
      ? `http://localhost:8080/api/search?q=${encodeURIComponent(searchQuery)}&subreddit=${encodeURIComponent(subreddit)}`
      : `http://localhost:8080/api/search?q=${encodeURIComponent(searchQuery)}`;

    fetch(apiUrl)
      .then((response) => {
        if (!response.ok) {
          throw new Error('Network response was not ok');
        }
        return response.json();
      })
      .then((data) => {
        setResult(data);
        setIsLoading(false);
      })
      .catch((error) => {
        console.error('Fetch error:', error);
        setError(error.message);
        setResult(null);
        setIsLoading(false);
      });
  };

  const handleKeyPress = (e) => {
    if (e.key === 'Enter') {
      handleSearch();
    }
  };

  return (
    <div className="app-container">
      <div className="container">
        <h1 className="title">ReddiSearch</h1>
        <p className="subtitle">Reddit answers at your fingertips</p>

        <div className="search-container">
          <input
            type="text"
            className="search-bar"
            placeholder="Search Reddit..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            onKeyPress={handleKeyPress}
          />
          <input
            type="text"
            className="search-bar"
            placeholder="Optional: subreddit (e.g. programming)"
            value={subreddit}
            onChange={(e) => setSubreddit(e.target.value.replace(/\s+/g, ''))}
            onKeyPress={handleKeyPress}
          />
          <button className="search-button" onClick={handleSearch}>
            Search
          </button>
        </div>

        <div className="results-container">
          {isLoading ? (
            <div className="loading">
              <div className="loading-spinner"></div>
            </div>
          ) : error ? (
            <p className="error-message">Error: {error}</p>
          ) : hasSearched && !result ? (
            <p className="no-results">
              No results found for "{searchQuery}". Try a different search term.
            </p>
          ) : result ? (
            <div className="result-item">
              <h3>Question: {result.query}</h3>
              <div className="answer" style={{whiteSpace: 'pre-wrap'}}>
                {result.answer}
              </div>
              <div className="result-meta">
                Processed in {result.processingTimeMs}ms
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

export default App;