import { useState } from "react"
import { Search, Loader2, MessageCircle, Clock, AlertCircle } from "lucide-react"
import "./App.css"

export default function Component() {
  const [searchQuery, setSearchQuery] = useState("")
  const [subreddit, setSubreddit] = useState("")
  const [result, setResult] = useState(null)
  const [isLoading, setIsLoading] = useState(false)
  const [hasSearched, setHasSearched] = useState(false)
  const [error, setError] = useState(null)

  const handleSearch = () => {
    if (!searchQuery.trim()) {
      setResult(null)
      setHasSearched(false)
      setError(null)
      return
    }

    setIsLoading(true)
    setHasSearched(true)
    setError(null)

    const apiUrl = subreddit
      ? `http://localhost:8080/api/search?q=${encodeURIComponent(searchQuery)}&subreddit=${encodeURIComponent(subreddit)}`
      : `http://localhost:8080/api/search?q=${encodeURIComponent(searchQuery)}`

    fetch(apiUrl)
      .then((response) => {
        if (!response.ok) {
          throw new Error("Network response was not ok")
        }
        return response.json()
      })
      .then((data) => {
        setResult(data)
        setIsLoading(false)
      })
      .catch((error) => {
        console.error("Fetch error:", error)
        setError(error.message)
        setResult(null)
        setIsLoading(false)
      })
  }

  const handleKeyPress = (e) => {
    if (e.key === "Enter") {
      handleSearch()
    }
  }

  return (
    <div className="app-container">
      <div className="main-content">
        {/* Header */}
        <div className="header">
          <h1 className="title">ReddiSearch</h1>
          <p className="subtitle">Reddit answers at your fingertips</p>
        </div>

        {/* Search Section */}
        <div className="search-card">
          <div className="search-content">
            <div className="input-group">
              <div className="input-container">
                <Search className="input-icon" />
                <input
                  type="text"
                  placeholder="Search Reddit..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  onKeyPress={handleKeyPress}
                  className="search-input"
                />
              </div>

              <div className="input-container">
                <MessageCircle className="input-icon" />
                <input
                  type="text"
                  placeholder="Optional: subreddit (e.g. programming)"
                  value={subreddit}
                  onChange={(e) => setSubreddit(e.target.value.replace(/\s+/g, ""))}
                  onKeyPress={handleKeyPress}
                  className="search-input"
                />
              </div>

              <button onClick={handleSearch} disabled={isLoading} className="search-button">
                {isLoading ? (
                  <>
                    <Loader2 className="button-icon spinning" />
                    Searching...
                  </>
                ) : (
                  <>
                    <Search className="button-icon" />
                    Search Reddit
                  </>
                )}
              </button>
            </div>
          </div>
        </div>

        {/* Results Section */}
        <div className="results-section">
          {isLoading && (
            <div className="result-card">
              <div className="loading-content">
                <Loader2 className="loading-spinner" />
                <p className="loading-text">Searching Reddit...</p>
              </div>
            </div>
          )}

          {error && (
            <div className="result-card error-card">
              <div className="error-content">
                <AlertCircle className="error-icon" />
                <p className="error-text">Error: {error}</p>
              </div>
            </div>
          )}

          {hasSearched && !result && !isLoading && !error && (
            <div className="result-card">
              <div className="no-results-content">
                <MessageCircle className="no-results-icon" />
                <p className="no-results-text">No results found for "{searchQuery}". Try a different search term.</p>
              </div>
            </div>
          )}

          {result && (
            <div className="result-card answer-card">
              <div className="answer-content">
                <div className="question-section">
                  <h3 className="question-title">Question: {result.query}</h3>
                </div>

                <div className="answer-section">
                  <div className="answer-text">{result.answer}</div>
                </div>

                <div className="meta-section">
                  <div className="meta-info">
                    <Clock className="meta-icon" />
                    <span className="meta-text">Processed in {result.processingTimeMs}ms</span>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
